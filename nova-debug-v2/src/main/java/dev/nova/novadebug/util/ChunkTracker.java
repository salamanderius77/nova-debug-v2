// ============================================================
// File: src/main/java/dev/nova/novadebug/util/ChunkTracker.java
//
// Core analysis engine for Nova Debug v2.
//
// Responsibilities:
//   - Maintain a thread-safe map of chunk → activity data
//   - Scan loaded chunks at configurable intervals
//   - Detect skeleton spawners, player proximity, chunk revisits
//   - Apply time-based decay so stale chunks fade out
//   - Expose a snapshot of active chunks for the renderer
//
// Performance design:
//   - Analysis is spread across multiple ticks (chunked iteration)
//   - No per-block scanning on every tick
//   - ConcurrentHashMap avoids lock contention between scan & render
//   - Max tracked chunks cap prevents unbounded memory growth
// ============================================================

package dev.nova.novadebug.util;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton-style tracker (one instance per GoofyDebug module lifecycle).
 *
 * <p>Call {@link #tick(Settings)} every game tick from the module's
 * {@code onTick()} event handler. Call {@link #reset()} when the
 * module is disabled.</p>
 */
public class ChunkTracker {

    // ── Internal state ───────────────────────────────────────────────────────

    /** Primary storage: chunkPos → activity data. Thread-safe for reads. */
    private final ConcurrentHashMap<ChunkPos2D, ChunkActivityData> chunkMap
            = new ConcurrentHashMap<>();

    /** Rolling iterator over loaded chunks — avoids scanning all at once. */
    private final Deque<ChunkPos2D> scanQueue = new ArrayDeque<>();

    /** Tick counter used to gate periodic work. */
    private int tickCounter = 0;

    /** Last tick on which we rebuilt the scan queue. */
    private long lastQueueRebuildTick = -1;

    // ── Settings snapshot (copied from module settings each tick) ────────────

    /**
     * Immutable settings bundle passed into each tick call.
     * The module fills this from its Setting<> fields.
     */
    public static class Settings {
        public final int    updateIntervalTicks;
        public final int    maxTrackedChunks;
        public final float  decaySpeed;          // score units per interval
        public final float  sensitivityMultiplier;
        public final boolean detectSpawners;
        public final boolean detectChunkActivity;
        public final boolean detectPlayers;
        public final float  minScoreThreshold;   // below this → chunk ignored in render

        public Settings(int updateIntervalTicks, int maxTrackedChunks,
                        float decaySpeed, float sensitivityMultiplier,
                        boolean detectSpawners, boolean detectChunkActivity,
                        boolean detectPlayers, float minScoreThreshold) {
            this.updateIntervalTicks  = updateIntervalTicks;
            this.maxTrackedChunks     = maxTrackedChunks;
            this.decaySpeed           = decaySpeed;
            this.sensitivityMultiplier= sensitivityMultiplier;
            this.detectSpawners       = detectSpawners;
            this.detectChunkActivity  = detectChunkActivity;
            this.detectPlayers        = detectPlayers;
            this.minScoreThreshold    = minScoreThreshold;
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Main per-tick entry point. Call from the module's onTick handler.
     *
     * @param settings Current module settings snapshot.
     */
    public void tick(Settings settings) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return;

        tickCounter++;

        // Only run analysis every N ticks to avoid frame drops.
        if (tickCounter % settings.updateIntervalTicks != 0) return;

        ClientWorld world = mc.world;
        long worldTime = world.getTime();

        // 1. Rebuild the scan queue if needed (every ~10 intervals).
        if (worldTime - lastQueueRebuildTick > (long) settings.updateIntervalTicks * 10L) {
            rebuildScanQueue(world, settings);
            lastQueueRebuildTick = worldTime;
        }

        // 2. Process a batch of chunks from the queue this tick.
        //    Limit batch size to keep tick time bounded (~30 chunks max).
        int batchSize = Math.min(30, Math.max(5, scanQueue.size() / 4));
        for (int i = 0; i < batchSize && !scanQueue.isEmpty(); i++) {
            ChunkPos2D pos = scanQueue.poll();
            if (pos == null) break;
            analyzeChunk(pos, world, settings, worldTime);
        }

        // 3. Apply decay and prune dead entries.
        applyDecayAndPrune(settings, worldTime);

        // 4. Enforce max-tracked-chunk cap (remove lowest-score entries).
        enforceChunkCap(settings.maxTrackedChunks);
    }

    /**
     * Returns an unmodifiable snapshot of all currently tracked chunks.
     * Safe to iterate from the render thread.
     */
    public Map<ChunkPos2D, ChunkActivityData> getTrackedChunks() {
        return Collections.unmodifiableMap(chunkMap);
    }

    /** Clears all tracked data (call on module disable). */
    public void reset() {
        chunkMap.clear();
        scanQueue.clear();
        tickCounter = 0;
        lastQueueRebuildTick = -1;
    }

    // ── Private analysis methods ─────────────────────────────────────────────

    /**
     * Populate the scan queue with all currently loaded chunk positions.
     * Only chunks within render distance of the player are considered.
     */
    private void rebuildScanQueue(ClientWorld world, Settings settings) {
        scanQueue.clear();

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        ChunkPos playerChunk = mc.player.getChunkPos();
        int renderRadius = Math.min(mc.options.getViewDistance().getValue(), 12);

        for (int dx = -renderRadius; dx <= renderRadius; dx++) {
            for (int dz = -renderRadius; dz <= renderRadius; dz++) {
                int cx = playerChunk.x + dx;
                int cz = playerChunk.z + dz;

                // Only add if the chunk is actually loaded.
                if (world.isChunkLoaded(cx, cz)) {
                    scanQueue.add(new ChunkPos2D(cx, cz));
                }
            }
        }
    }

    /**
     * Analyze a single chunk and update its activity data.
     *
     * <p>We examine only underground blocks (Y < 64) to focus on
     * stashes and farms, and we sample rather than scan every block.</p>
     */
    private void analyzeChunk(ChunkPos2D pos, ClientWorld world,
                               Settings settings, long worldTime) {
        WorldChunk chunk = world.getChunk(pos.x, pos.z);
        if (chunk == null || chunk.isEmpty()) return;

        // Get or create the activity data entry for this chunk.
        ChunkActivityData data = chunkMap.computeIfAbsent(
                pos, k -> new ChunkActivityData(worldTime));

        float mult = settings.sensitivityMultiplier;

        // ── Detection: Skeleton Spawners ───────────────────────────────────
        if (settings.detectSpawners) {
            int spawnerCount = countSpawnersUnderground(chunk, pos);
            if (spawnerCount > 0) {
                // Each spawner adds a large score boost.
                data.addSpawnerScore(spawnerCount * 25f * mult);
            }
        }

        // ── Detection: Chunk Update / Revisit Activity ─────────────────────
        if (settings.detectChunkActivity) {
            // We score every analysis pass as a "revisit" of this chunk.
            // Newly created entries start at 0; repeated passes push score up.
            float revisitBoost = Math.min(data.getRevisitCount() * 1.5f, 20f) * mult;
            data.addChunkUpdateScore(revisitBoost);
        }

        // ── Detection: Player Presence ─────────────────────────────────────
        if (settings.detectPlayers) {
            int nearbyPlayers = countNearbyPlayers(world, pos);
            if (nearbyPlayers > 0) {
                data.addPlayerScore(nearbyPlayers * 30f * mult);
            }
        }
    }

    /**
     * Counts skeleton spawners in underground layers of the chunk.
     * Samples every 4 blocks horizontally and checks Y -64 to 63.
     */
    private int countSpawnersUnderground(WorldChunk chunk, ChunkPos2D pos) {
        int count = 0;
        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();

        // Sample grid: 4×4 columns per chunk (every 4 blocks).
        for (int lx = 0; lx < 16; lx += 4) {
            for (int lz = 0; lz < 16; lz += 4) {
                int worldX = baseX + lx;
                int worldZ = baseZ + lz;

                // Scan underground layers only (below sea level).
                for (int y = -64; y < 64; y++) {
                    BlockPos bp = new BlockPos(worldX, y, worldZ);
                    if (chunk.getBlockState(bp).isOf(Blocks.SPAWNER)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Counts players (other than the local player) whose chunk matches pos.
     */
    private int countNearbyPlayers(ClientWorld world, ChunkPos2D pos) {
        int count = 0;
        for (AbstractClientPlayerEntity player : world.getPlayers()) {
            // Skip local player — we want OTHER players.
            if (player == MinecraftClient.getInstance().player) continue;

            ChunkPos playerChunk = player.getChunkPos();
            if (playerChunk.x == pos.x && playerChunk.z == pos.z) {
                count++;
            }
        }
        return count;
    }

    /**
     * Applies decay to all tracked chunks and removes fully decayed entries.
     */
    private void applyDecayAndPrune(Settings settings, long worldTime) {
        Iterator<Map.Entry<ChunkPos2D, ChunkActivityData>> it
                = chunkMap.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<ChunkPos2D, ChunkActivityData> entry = it.next();
            ChunkActivityData data = entry.getValue();

            data.decay(settings.decaySpeed, worldTime);

            if (data.isFullyDecayed()) {
                it.remove();
            }
        }
    }

    /**
     * If tracked chunk count exceeds the cap, remove the lowest-scored chunks.
     */
    private void enforceChunkCap(int maxChunks) {
        if (chunkMap.size() <= maxChunks) return;

        // Collect entries sorted by score ascending (lowest first = remove first).
        List<Map.Entry<ChunkPos2D, ChunkActivityData>> entries
                = new ArrayList<>(chunkMap.entrySet());
        entries.sort(Comparator.comparingDouble(e -> e.getValue().getTotalScore()));

        int toRemove = chunkMap.size() - maxChunks;
        for (int i = 0; i < toRemove && i < entries.size(); i++) {
            chunkMap.remove(entries.get(i).getKey());
        }
    }
}
