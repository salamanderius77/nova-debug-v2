package dev.nova.novadebug.modules;

import dev.nova.novadebug.NovaDebugAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.text.Text;

public class AutoRelog extends Module {

    public AutoRelog() {
        super(NovaDebugAddon.CATEGORY, "Auto Relog", "Disconnects from the server when you go below Y -3.");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        if (mc.player.getY() < -3) {
            mc.world.disconnect();
            mc.disconnect(new DisconnectedScreen(
                new MultiplayerScreen(new TitleScreen()),
                Text.literal("Auto Relog"),
                Text.literal("Disconnected: went below Y -3.")
            ));
        }
    }
}
