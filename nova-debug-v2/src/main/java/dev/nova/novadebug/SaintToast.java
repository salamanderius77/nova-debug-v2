package dev.nova.novadebug;

import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.font.TextRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared custom toast notifier for Saint's Addon.
 * Pops up bottom-right of the screen, fades in, holds 5s, fades out.
 *
 * Usage from a module:
 *   SaintToast.get().show("Player Found!", playerName);
 *
 * Must be registered once with the event bus, e.g. in NovaDebugAddon.onInitialize():
 *   MeteorClient.EVENT_BUS.subscribe(SaintToast.get());
 */
public class SaintToast {

    private static final SaintToast INSTANCE = new SaintToast();
    public static SaintToast get() { return INSTANCE; }

    private static final long FADE_IN_MS = 250;
    private static final long HOLD_MS = 5000;
    private static final long FADE_OUT_MS = 400;
    private static final long TOTAL_MS = FADE_IN_MS + HOLD_MS + FADE_OUT_MS;

    private static final int WIDTH = 170;
    private static final int HEIGHT = 34;
    private static final int MARGIN_RIGHT = 10;
    private static final int MARGIN_BOTTOM = 40;
    private static final int GAP = 5;

    private static final int ACCENT = 0xB400FF; // purple accent line

    private static class Entry {
        final String title;
        final String subtitle;
        final long startTime = System.currentTimeMillis();

        Entry(String title, String subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }

        float alpha() {
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed < FADE_IN_MS) return Math.max(0f, elapsed / (float) FADE_IN_MS);
            if (elapsed < FADE_IN_MS + HOLD_MS) return 1f;
            if (elapsed < TOTAL_MS) {
                long fadeElapsed = elapsed - FADE_IN_MS - HOLD_MS;
                return Math.max(0f, 1f - (fadeElapsed / (float) FADE_OUT_MS));
            }
            return 0f;
        }

        boolean expired() {
            return System.currentTimeMillis() - startTime >= TOTAL_MS;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    private SaintToast() {}

    public void show(String title, String subtitle) {
        synchronized (entries) {
            entries.add(new Entry(title, subtitle));
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    private void onRender2D(Render2DEvent event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getWindow() == null) return;

        synchronized (entries) {
            entries.removeIf(Entry::expired);
            if (entries.isEmpty()) return;

            DrawContext ctx = event.drawContext;
            if (ctx == null) return;

            int screenW = mc.getWindow().getScaledWidth();
            int screenH = mc.getWindow().getScaledHeight();
            TextRenderer tr = mc.textRenderer;

            int baseX = screenW - WIDTH - MARGIN_RIGHT;
            int baseY = screenH - MARGIN_BOTTOM - HEIGHT;

            for (int i = 0; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                float alpha = entry.alpha();
                if (alpha <= 0f) continue;

                int y = baseY - i * (HEIGHT + GAP);
                int bgAlpha = (int) (alpha * 200) & 0xFF;
                int textAlpha = (int) (alpha * 255) & 0xFF;
                int subAlpha = (int) (alpha * 180) & 0xFF;

                int bgColor = (bgAlpha << 24) | 0x141418;
                int accentColor = (textAlpha << 24) | ACCENT;
                int titleColor = (textAlpha << 24) | 0xFFFFFF;
                int subColor = (subAlpha << 24) | 0xAAAAAA;

                ctx.fill(baseX, y, baseX + WIDTH, y + HEIGHT, bgColor);
                ctx.fill(baseX, y, baseX + 3, y + HEIGHT, accentColor);

                ctx.drawText(tr, entry.title, baseX + 10, y + 7, titleColor, false);
                ctx.drawText(tr, entry.subtitle, baseX + 10, y + 19, subColor, false);
            }
        }
    }
}
