package dev.nova.novadebug.util;

import net.minecraft.block.SpawnerBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;

public class ChunkTracker {

    // ── Settings ─────────────────────────────────────────────────────────────

    public static class Settings {
        public final int     updateIntervalTicks;
        public final int     maxTrackedChunks;
        public final float   decaySpeed;
        public final float   sensitivityMultiplier;
        public final boolean detectSpawners;
        public final boolean detectChunkActivity;
        public final boolean detectPlayers;
        public final boolean novaFinderEnabled;
        public final float   minScoreThreshold;

        public Settings(int updateIntervalTicks, int maxTrackedChunks,
                        float decaySpeed, float sensitivityMultiplier,
                        boolean detectSpawners, boolean detectChunkActivity,
                        boolean detectPlayers, boolean novaFinderEnabled,
                        float minScoreThreshold) {
            this.updateIntervalTicks  = updateIntervalTicks;
            this.maxTrackedChunks     = maxTrackedChunks;
            this.decaySpeed           = decaySpeed;
            this.sensitivityMultiplier = sensitivityMultiplier;
            this.detectSpawners       = detectSpawners;
            this.detectChunkActivity  = detectChunkActivity;
            this.detectPlayers        = detectPlayers;
            this.novaFinderEnabled    = novaFinderEnabled;
            this.minScoreThreshold    = minScoreThreshold;
        }
    }

    // ── DetectionEvent ────────────────────────────────────────────────────────

    public static class DetectionEvent {
        public enum Kind { PLAYER, SPAWNER, ACTIVITY, NOVA }

        public final Kind   kind;
        public final String playerName;   // PLAYER events
        public final int    spawnerCount; // SPAWNER events
        public final double x, y, z;

        private DetectionEvent(Kind kind, String playerName, int spawnerCount,
                               double x, double y, double z) {
            this.kind         = kind;
            this.playerName   = playerName;
            this.spawnerCount = spawnerCount;
            this.x = x; this.y = y; this.z = z;
        }

        public static DetectionEvent player(String name, double x, double y, double z) {
            return new DetectionEvent(Kind.PLAYER, name, 0, x, y, z);
        }

        public static DetectionEvent spawner(int count, double x, double y, double z) {
            return new DetectionEvent(Kind.SPAWNER, null, count, x, y, z);
        }

        public static DetectionEvent activity(double x, double y, double z) {
            return new DetectionEvent(Kind.ACTIVITY, null, 0, x, y, z);
        }

        public static DetectionEvent nova(double x, double y, double z) {
            return new DetectionEvent(Kind.NOVA, null, 0, x, y, z);
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private final Map<ChunkPos2D, ChunkActivityData> trackedChunks = new LinkedHashMap<>();
    private int tickCounter = 0;

    // Track which players we've already fired events for (avoid spam)
    private final Set<String> knownPlayers = new HashSet<>();

    // ── Public API ────────────────────────────────────────────────────────────

    /** Clear all tracked data. */
    public void reset() {
        trackedChunks.clear();
        knownPlayers.clear();
        tickCounter = 0;
    }

    /** Returns a read-only view of all tracked chunks. */
    public Map<ChunkPos2D, ChunkActivityData> getTrackedChunks() {
        return Collections.unmodifiableMap(trackedChunks);
    }

    /**
     * Tick player detection. Always runs every tick regardless of interval.
     * @return list of new DetectionEvents for players found this tick.
     */
    public List<DetectionEvent> tickPlayers(Settings s) {
        List<DetectionEvent> events = new ArrayList<>();
        if (!s.detectPlayers) return events;

        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world  = mc.world;
        ClientPlayerEntity self = mc.player;
        if (world == null || self == null) return events;

        for (OtherClientPlayerEntity player : world.getPlayers()) {
            String name = player.getName().getString();
            ChunkPos2D cpos = new ChunkPos2D(
                (int) Math.floor(player.getX()) >> 4,
                (int) Math.floor(player.getZ()) >> 4
            );

            ChunkActivityData data = trackedChunks.computeIfAbsent(
                cpos, k -> new ChunkActivityData());
            data.markPlayerDetected();

            // Fire event only the first time we see this player
            if (!knownPlayers.contains(name)) {
                knownPlayers.add(name);
                events.add(DetectionEvent.player(
                    name, player.getX(), player.getY(), player.getZ()));
            }
        }

        // Clean up players that left
        Set<String> currentNames = new HashSet<>();
        for (OtherClientPlayerEntity p : world.getPlayers())
            currentNames.add(p.getName().getString());
        knownPlayers.retainAll(currentNames);

        return events;
    }

    /**
     * Tick spawner / activity / nova analysis. Runs on the configured interval.
     * @return list of DetectionEvents fired this tick.
     */
    public List<DetectionEvent> tickAnalysis(Settings s) {
        List<DetectionEvent> events = new ArrayList<>();

        tickCounter++;
        if (tickCounter < s.updateIntervalTicks) return events;
        tickCounter = 0;

        MinecraftClient mc  = MinecraftClient.getInstance();
        ClientWorld world   = mc.world;
        ClientPlayerEntity player = mc.player;
        if (world == null || player == null) return events;

        int playerCX = (int) Math.floor(player.getX()) >> 4;
        int playerCZ = (int) Math.floor(player.getZ()) >> 4;
        int radius   = 8; // scan a fixed radius around the player

        for (int cx = playerCX - radius; cx <= playerCX + radius; cx++) {
            for (int cz = playerCZ - radius; cz <= playerCZ + radius; cz++) {
                WorldChunk chunk = world.getChunk(cx, cz);
                if (chunk == null) continue;

                ChunkPos2D cpos = new ChunkPos2D(cx, cz);

                // ── Spawner scan ──────────────────────────────────────────────
                if (s.detectSpawners) {
                    int spawnerCount = countSpawnersInChunk(chunk);
                    if (spawnerCount > 0) {
                        ChunkActivityData data = trackedChunks.computeIfAbsent(
                            cpos, k -> new ChunkActivityData());
                        data.markSpawnersFound(spawnerCount);

                        double bx = cx * 16 + 8;
                        double bz = cz * 16 + 8;
                        events.add(DetectionEvent.spawner(spawnerCount, bx, 64, bz));
                    }
                }

                // ── Activity / revisit scan ───────────────────────────────────
                if (s.detectChunkActivity) {
                    ChunkActivityData existing = trackedChunks.get(cpos);
                    if (existing != null) {
                        existing.tickActivity(s.decaySpeed);
                    }
                }

                // ── Nova Finder scan ──────────────────────────────────────────
                if (s.novaFinderEnabled) {
                    if (isSuspiciousChunk(chunk)) {
                        ChunkActivityData data = trackedChunks.computeIfAbsent(
                            cpos, k -> new ChunkActivityData());
                        data.markNovaFound();

                        double bx = cx * 16 + 8;
                        double bz = cz * 16 + 8;
                        events.add(DetectionEvent.nova(bx, 64, bz));
                    }
                }
            }
        }

        // Prune excess entries
        while (trackedChunks.size() > s.maxTrackedChunks) {
            trackedChunks.remove(trackedChunks.keySet().iterator().next());
        }

        return events;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int countSpawnersInChunk(WorldChunk chunk) {
        int count = 0;
        ChunkPos cp = chunk.getPos();
        int x1 = cp.getStartX(), z1 = cp.getStartZ();
        int x2 = cp.getEndX(),   z2 = cp.getEndZ();

        // Scan underground (Y -64 to 63)
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                for (int y = -64; y <= 63; y++) {
                    if (chunk.getBlockState(new BlockPos(x, y, z))
                             .getBlock() instanceof SpawnerBlock) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** Heuristic: chunks with unusually high block-update counts are suspicious. */
    private boolean isSuspiciousChunk(WorldChunk chunk) {
        // Simple heuristic — non-empty chunks that have been loaded
        // could be expanded with real packet-level analysis if desired.
        return !chunk.isEmpty();
    }
}
