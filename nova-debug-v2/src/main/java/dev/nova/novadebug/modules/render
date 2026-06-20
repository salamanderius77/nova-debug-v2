package dev.nova.novadebug.modules;

import dev.nova.novadebug.NovaDebugAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

public class RenderMeta extends Module {

    private static final int TARGET_DISTANCE = 2;
    private static final int RESTORE_DISTANCE = 32;
    private static final int RESTORE_DELAY_TICKS = 10; // 0.5s at 20 ticks/sec

    private boolean triggered = false;
    private int restoreTimer = 0;

    public RenderMeta() {
        super(NovaDebugAddon.CATEGORY, "Render Meta", "Drops render/simulation distance to 2 when below Y -3, then restores to 32 after 0.5s.");
    }

    @Override
    public void onActivate() {
        triggered = false;
        restoreTimer = 0;
    }

    @Override
    public void onDeactivate() {
        // Restore distances if deactivated mid-cycle so the player isn't stuck at 2 chunks.
        setDistances(RESTORE_DISTANCE);
        triggered = false;
        restoreTimer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.options == null) return;

        if (mc.player.getY() < -3) {
            if (!triggered) {
                triggered = true;
                restoreTimer = 0;
                setDistances(TARGET_DISTANCE);
            }
        }

        if (triggered) {
            restoreTimer++;
            if (restoreTimer >= RESTORE_DELAY_TICKS) {
                setDistances(RESTORE_DISTANCE);
                triggered = false;
                restoreTimer = 0;
            }
        }
    }

    private void setDistances(int distance) {
        mc.options.viewDistance().setValue(distance);
        mc.options.simulationDistance().setValue(distance);
        // Apply the chunk render distance change immediately.
        mc.worldRenderer.scheduleTerrainUpdate();
        mc.options.write();
    }
}
