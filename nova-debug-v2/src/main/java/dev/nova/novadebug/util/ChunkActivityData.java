package dev.nova.novadebug.util;

public class ChunkActivityData {

    public enum ActivityLevel { LOW, MEDIUM, HIGH }

    private int  spawnerCount    = 0;
    private boolean playerDetected = false;
    private boolean novaDetected   = false;
    private float   activityScore  = 0f;

    // ── Setters (called by ChunkTracker) ──────────────────────────────────────

    public void markSpawnersFound(int count) {
        this.spawnerCount = count;
    }

    public void markPlayerDetected() {
        this.playerDetected = true;
    }

    public void markNovaFound() {
        this.novaDetected = true;
    }

    public void tickActivity(float decaySpeed) {
        activityScore = Math.max(0f, activityScore - decaySpeed);
        activityScore += 1f; // bump on each visit
    }

    // ── Queries (called by ChunkRenderer) ────────────────────────────────────

    public boolean hasSpawners()       { return spawnerCount > 0; }
    public boolean hasPlayerActivity() { return playerDetected; }
    public boolean hasChunkActivity()  { return activityScore > 0f; }
    public boolean hasNova()           { return novaDetected; }

    public int   getSpawnerCount()  { return spawnerCount; }
    public float getActivityScore() { return activityScore; }

    /**
     * Returns an ActivityLevel based on the chunk's total "heat",
     * or null if below the threshold.
     */
    public ActivityLevel getActivityLevel(float minThreshold) {
        float score = activityScore
            + (spawnerCount * 5f)
            + (playerDetected ? 10f : 0f)
            + (novaDetected   ?  8f : 0f);

        if (score < minThreshold) return null;
        if (score < 10f)  return ActivityLevel.LOW;
        if (score < 25f)  return ActivityLevel.MEDIUM;
        return ActivityLevel.HIGH;
    }
}
