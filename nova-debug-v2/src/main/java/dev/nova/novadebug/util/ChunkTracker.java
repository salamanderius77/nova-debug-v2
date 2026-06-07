package dev.nova.novadebug.util;
import net.minecraft.util.math.ChunkPos;
public class ChunkTracker {
    public static class Settings {
        public final int updateIntervalTicks, maxTrackedChunks;
        public final float decaySpeed, sensitivityMultiplier, minScoreThreshold;
        public final boolean detectSpawners, detectChunkActivity, detectPlayers;
        public Settings(int ui, int mc, float ds, float sm, boolean ds2, boolean dca, boolean dp, float mst) {
            updateIntervalTicks=ui; maxTrackedChunks=mc; decaySpeed=ds;
            sensitivityMultiplier=sm; detectSpawners=ds2; detectChunkActivity=dca;
            detectPlayers=dp; minScoreThreshold=mst;
        }
    }
}
