package dev.nova.novadebug.util;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.class_1923;
import net.minecraft.class_2246;
import net.minecraft.class_2338;
import net.minecraft.class_2818;
import net.minecraft.class_310;
import net.minecraft.class_638;
import net.minecraft.class_742;

public class ChunkTracker {

    public static class DetectionEvent {

        public enum Kind { PLAYER, SPAWNER }

        public final Kind   kind;
        public final String playerName;
        public final double x, y, z;
        public final int    spawnerCount;

        private DetectionEvent(Kind kind, String playerName,
                               double x, double y, double z, int spawnerCount) {
            this.kind         = kind;
            this.playerName   = playerName;
            this.x            = x;
            this.y            = y;
            this.z            = z;
            this.spawnerCount = spawnerCount;
        }

        static DetectionEvent player(String name, double x, double y, double z) {
            return new DetectionEvent(Kind.PLAYER, name, x, y, z, 0);
        }

        static DetectionEvent spawner(int count, double cx, double cz) {
            return new DetectionEvent(Kind.SPAWNER, null, cx, -1, cz, count);
        }
    }

    private final ConcurrentHashMap<ChunkPos2D, ChunkActivityData> chunkMap
            = new ConcurrentHashMap<>();
    private final Deque<ChunkPos2D> scanQueue          = new ArrayDeque<>();
    private final Set<ChunkPos2D> knownPlayerChunks    = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos2D> toastedSpawnerChunks = ConcurrentHashMap.newKeySet();

    private int  tickCounter          = 0;
    private long lastQueueRebuildTick = -1;

    public static class Settings {
        public final int     updateIntervalTicks;
        public final int     maxTrackedChunks;
        public final float   decaySpeed;
        public final float   sensitivityMultiplier;
        public final boolean detectSpawners;
        public final boolean detectChunkActivity;
        public final boolean detectPlayers;
        public final float   minScoreThreshold;

        public Settings(int updateIntervalTicks, int maxTrackedChunks,
                        float decaySpeed, float sensitivityMultiplier,
                        boolean detectSpawners, boolean detectChunkActivity,
                        boolean detectPlayers, float minScoreThreshold) {
            this.updateIntervalTicks   = updateIntervalTicks;
            this.maxTrackedChunks      = maxTrackedChunks;
            this.decaySpeed            = decaySpeed;
            this.sensitivityMultiplier = sensitivityMultiplier;
            this.detectSpawners        = detectSpawners;
            this.detectChunkActivity   = detectChunkActivity;
            this.detectPlayers         = detectPlayers;
            this.minScoreThreshold     = minScoreThreshold;
        }
    }

    public List<DetectionEvent> tickPlayers(Settings settings) {
        List<DetectionEvent> events = new ArrayList<>();
        if (!settings.detectPlayers) return events;

        class_310 mc = class_310.method_1551();
        if (mc.field_1687 == null || mc.field_1724 == null) return events;

        class_638 world = mc.field_1687;
        long worldTime  = world.method_8510();

        for (class_742 player : world.method_18456()) {
            if (player == mc.field_1724) continue;

            class_1923 mcPos = player.method_31476();
            ChunkPos2D pos   = new ChunkPos2D(mcPos);

            ChunkActivityData data = chunkMap.computeIfAbsent(
                    pos, k -> new ChunkActivityData(worldTime));
            data.addPlayerScore(30f * settings.sensitivityMultiplier);

            if (knownPlayerChunks.add(pos)) {
                double px = player.method_23317();
                double py = player.getY();
                double pz = player.method_23321();
                String name = player.getName().getString();
                events.add(DetectionEvent.player(name, px, py, pz));
            }
        }

        Set<ChunkPos2D> currentPlayerChunks = new HashSet<>();
        for (class_742 player : world.method_18456()) {
            if (player == mc.field_1724) continue;
            currentPlayerChunks.add(new ChunkPos2D(player.method_31476()));
        }
        knownPlayerChunks.retainAll(currentPlayerChunks);

        return events;
    }

    public List<DetectionEvent> tickAnalysis(Settings settings) {
        List<DetectionEvent> events = new ArrayList<>();

        class_310 mc = class_310.method_1551();
        if (mc.field_1687 == null || mc.field_1724 == null) return events;

        tickCounter++;
        if (tickCounter % settings.updateIntervalTicks != 0) return events;

        class_638 world = mc.field_1687;
        long worldTime  = world.method_8510();

        if (worldTime - lastQueueRebuildTick > (long) settings.updateIntervalTicks * 10L) {
            rebuildScanQueue(world, settings);
            lastQueueRebuildTick = worldTime;
        }

        int batchSize = Math.min(30, Math.max(5, scanQueue.size() / 4));
        for (int i = 0; i < batchSize && !scanQueue.isEmpty(); i++) {
            ChunkPos2D pos = scanQueue.poll();
            if (pos == null) break;
            DetectionEvent ev = analyzeChunk(pos, world, settings, worldTime);
            if (ev != null) events.add(ev);
        }

        applyDecayAndPrune(settings, worldTime);
        enforceChunkCap(settings.maxTrackedChunks);

        return events;
    }

    public Map<ChunkPos2D, ChunkActivityData> getTrackedChunks() {
        return Collections.unmodifiableMap(chunkMap);
    }

    public void reset() {
        chunkMap.clear();
        scanQueue.clear();
        knownPlayerChunks.clear();
        toastedSpawnerChunks.clear();
        tickCounter = 0;
        lastQueueRebuildTick = -1;
    }

    private void rebuildScanQueue(class_638 world, Settings settings) {
        scanQueue.clear();
        class_310 mc = class_310.method_1551();
        if (mc.field_1724 == null) return;

        class_1923 playerChunk = mc.field_1724.method_31476();
        int renderRadius = Math.min(mc.field_1690.method_42503().method_41753(), 12);

        for (int dx = -renderRadius; dx <= renderRadius; dx++) {
            for (int dz = -renderRadius; dz <= renderRadius; dz++) {
                int cx = playerChunk.field_9181 + dx;
                int cz = playerChunk.field_9180 + dz;
                if (world.method_8393(cx, cz)) {
                    scanQueue.add(new ChunkPos2D(cx, cz));
                }
            }
        }
    }

    private DetectionEvent analyzeChunk(ChunkPos2D pos, class_638 world,
                                         Settings settings, long worldTime) {
        class_2818 chunk = world.method_8497(pos.x, pos.z);
        if (chunk == null || chunk.method_12223()) return null;

        ChunkActivityData data = chunkMap.computeIfAbsent(
                pos, k -> new ChunkActivityData(worldTime));

        float mult = settings.sensitivityMultiplier;
        DetectionEvent event = null;

        if (settings.detectSpawners) {
            int spawnerCount = countSpawnersUnderground(chunk, pos);
            if (spawnerCount > 0) {
                data.addSpawnerScore(spawnerCount * 25f * mult);
                if (toastedSpawnerChunks.add(pos)) {
                    event = DetectionEvent.spawner(
                        spawnerCount, pos.getCenterX(), pos.getCenterZ());
                }
            }
        }

        if (settings.detectChunkActivity) {
            float revisitBoost = Math.min(data.getRevisitCount() * 1.5f, 20f) * mult;
            data.addChunkUpdateScore(revisitBoost);
        }

        return event;
    }

    private int countSpawnersUnderground(class_2818 chunk, ChunkPos2D pos) {
        int count = 0;
        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int worldX = baseX + lx;
                int worldZ = baseZ + lz;
                for (int y = -64; y < 64; y++) {
                    class_2338 bp = new class_2338(worldX, y, worldZ);
                    if (chunk.method_8320(bp).method_27852(class_2246.field_10260)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private void applyDecayAndPrune(Settings settings, long worldTime) {
        Iterator<Map.Entry<ChunkPos2D, ChunkActivityData>> it
                = chunkMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ChunkPos2D, ChunkActivityData> entry = it.next();
            entry.getValue().decay(settings.decaySpeed, worldTime);
            if (entry.getValue().isFullyDecayed()) {
                toastedSpawnerChunks.remove(entry.getKey());
                it.remove();
            }
        }
    }

    private void enforceChunkCap(int maxChunks) {
        if (chunkMap.size() <= maxChunks) return;

        List<Map.Entry<ChunkPos2D, ChunkActivityData>> entries
                = new ArrayList<>(chunkMap.entrySet());
        entries.sort(Comparator.comparingDouble(e -> e.getValue().getTotalScore()));

        int toRemove = chunkMap.size() - maxChunks;
        for (int i = 0; i < toRemove && i < entries.size(); i++) {
            ChunkPos2D key = entries.get(i).getKey();
            chunkMap.remove(key);
            toastedSpawnerChunks.remove(key);
        }
    }
}
