// ============================================================
// File: src/main/java/dev/nova/novadebug/util/ChunkActivityData.java
//
// Stores and manages activity score data for a single chunk.
// Each tracked chunk gets one of these objects, held in the
// central ConcurrentHashMap inside ChunkTracker.
// ============================================================

package dev.nova.novadebug.util;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Holds the computed activity score for one chunk position.
 *
 * <p>Score contributions come from multiple detection sources:
 * <ul>
 *   <li>Skeleton spawners detected underground</li>
 *   <li>Repeated chunk load / update events</li>
 *   <li>Player presence (nearby players)</li>
 * </ul>
 *
 * <p>Scores decay over time so stale data does not permanently
 * mark chunks as suspicious.</p>
 */
public class ChunkActivityData {

    // ── Score components ────────────────────────────────────────────────────

    /** Contribution from skeleton spawners found underground. */
    private volatile float spawnerScore;

    /** Contribution from repeated chunk load / update events. */
    private volatile float chunkUpdateScore;

    /** Contribution from detected nearby players. */
    private volatile float playerScore;

    /** Combined cached score (updated by recalculate()). */
    private volatile float totalScore;

    // ── Metadata ────────────────────────────────────────────────────────────

    /** World time (ticks) when this chunk was first added to the tracker. */
    private final long firstSeenTick;

    /** World time (ticks) of the most recent score update. */
    private volatile long lastUpdateTick;

    /** How many times this chunk has been revisited / re-evaluated. */
    private final AtomicInteger revisitCount = new AtomicInteger(0);

    // ── Constructor ─────────────────────────────────────────────────────────

    /**
     * @param currentTick The world time in ticks when this entry is created.
     */
    public ChunkActivityData(long currentTick) {
        this.firstSeenTick  = currentTick;
        this.lastUpdateTick = currentTick;
    }

    // ── Score manipulation ───────────────────────────────────────────────────

    /**
     * Add to the spawner score component.
     *
     * @param amount Amount to add (positive).
     */
    public synchronized void addSpawnerScore(float amount) {
        spawnerScore = Math.min(spawnerScore + amount, 100f);
        recalculate();
    }

    /**
     * Add to the chunk-update score component (chunk revisit / load events).
     *
     * @param amount Amount to add (positive).
     */
    public synchronized void addChunkUpdateScore(float amount) {
        chunkUpdateScore = Math.min(chunkUpdateScore + amount, 100f);
        revisitCount.incrementAndGet();
        recalculate();
    }

    /**
     * Add to the player-presence score component.
     *
     * @param amount Amount to add (positive).
     */
    public synchronized void addPlayerScore(float amount) {
        playerScore = Math.min(playerScore + amount, 100f);
        recalculate();
    }

    /**
     * Apply time-based decay to all score components.
     *
     * @param decayAmount Amount to subtract from each component this tick.
     * @param currentTick The current world tick (used to update lastUpdateTick).
     */
    public synchronized void decay(float decayAmount, long currentTick) {
        spawnerScore     = Math.max(0, spawnerScore     - decayAmount);
        chunkUpdateScore = Math.max(0, chunkUpdateScore - decayAmount);
        playerScore      = Math.max(0, playerScore      - decayAmount);
        lastUpdateTick   = currentTick;
        recalculate();
    }

    /** Recomputes totalScore as a weighted sum of all components. */
    private void recalculate() {
        // Weights: spawners are high-value signals; player presence is highest.
        totalScore = (spawnerScore * 0.35f)
                   + (chunkUpdateScore * 0.30f)
                   + (playerScore * 0.35f);
    }

    // ── Activity classification ──────────────────────────────────────────────

    /**
     * Activity tier based on current total score.
     */
    public enum ActivityLevel {
        LOW,    // 1 – 30
        MEDIUM, // 31 – 65
        HIGH    // 66+
    }

    /**
     * Returns the activity tier for this chunk.
     * Returns {@code null} if the score is below the minimum threshold.
     *
     * @param minScore Minimum score to consider this chunk "active".
     */
    public ActivityLevel getActivityLevel(float minScore) {
        if (totalScore < minScore) return null;
        if (totalScore < 30f)     return ActivityLevel.LOW;
        if (totalScore < 65f)     return ActivityLevel.MEDIUM;
        return ActivityLevel.HIGH;
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public float getTotalScore()      { return totalScore; }
    public float getSpawnerScore()    { return spawnerScore; }
    public float getChunkUpdateScore(){ return chunkUpdateScore; }
    public float getPlayerScore()     { return playerScore; }
    public long  getFirstSeenTick()   { return firstSeenTick; }
    public long  getLastUpdateTick()  { return lastUpdateTick; }
    public int   getRevisitCount()    { return revisitCount.get(); }

    /** True if all score components have fully decayed to zero. */
    public boolean isFullyDecayed() {
        return totalScore <= 0f;
    }
}
