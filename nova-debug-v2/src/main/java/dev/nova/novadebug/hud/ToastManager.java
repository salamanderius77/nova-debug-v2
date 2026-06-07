package dev.nova.novadebug.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class ToastManager {

    private static final long FADE_IN_MS  = 300;
    private static final long HOLD_MS     = 4_000;
    private static final long FADE_OUT_MS = 500;
    private static final long TOTAL_MS    = FADE_IN_MS + HOLD_MS + FADE_OUT_MS;

    private static final int TOAST_WIDTH   = 220;
    private static final int TOAST_PADDING = 8;
    private static final int TOAST_GAP     = 4;
    private static final int MARGIN_RIGHT  = 8;
    private static final int MARGIN_TOP    = 8;

    private final List<Toast> toasts = new ArrayList<>();

    public static class Toast {

        public enum Kind { PLAYER, SPAWNER }

        final Kind   kind;
        final String title;
        final String line2;
        final String line3;
        final long   createdMs;

        private Toast(Kind kind, String title, String line2, String line3) {
            this.kind      = kind;
            this.title     = title;
            this.line2     = line2;
            this.line3     = line3;
            this.createdMs = System.currentTimeMillis();
        }

        public static Toast forPlayer(String playerName, double x, double y, double z) {
            return new Toast(
                Kind.PLAYER,
                "\u26A0 Player Detected",
                playerName,
                String.format("XYZ: %.0f, %.0f, %.0f", x, y, z)
            );
        }

        public static Toast forSpawners(int count, double x, double y, double z) {
            String pile = count == 1 ? "1 spawner pile" : count + " spawner piles";
            return new Toast(
                Kind.SPAWNER,
                "\u2620 Spawner Detected",
                pile,
                String.format("XYZ: %.0f, ?, %.0f", x, z)
            );
        }
    }

    public void add(Toast toast) {
        synchronized (toasts) {
            toasts.add(toast);
        }
    }

    public void render(DrawContext context) {
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr    = mc.textRenderer;
        int screenW        = mc.getWindow().getScaledWidth();
        long now           = System.currentTimeMillis();

        synchronized (toasts) {
            Iterator<Toast> it = toasts.iterator();
            while (it.hasNext()) {
                if (now - it.next().createdMs >= TOTAL_MS) it.remove();
            }

            int yOffset = MARGIN_TOP;

            for (Toast toast : toasts) {
                long age    = now - toast.createdMs;
                float alpha = computeAlpha(age);
                if (alpha <= 0f) continue;

                int a = (int)(alpha * 255);

                boolean hasLine3 = toast.line3 != null;
                int lineH  = tr.fontHeight + 2;
                int lines  = hasLine3 ? 3 : 2;
                int toastH = TOAST_PADDING * 2 + lines * lineH;

                int x = screenW - TOAST_WIDTH - MARGIN_RIGHT;
                int y = yOffset;

                int bgAlpha   = (int)(alpha * 180);
                int bgColor   = argb(bgAlpha, 15, 15, 20);
                int borderCol = toast.kind == Toast.Kind.PLAYER
                    ? argb(a, 180, 0, 255)
                    : argb(a, 255, 140, 0);

                context.fill(x, y, x + TOAST_WIDTH, y + toastH, bgColor);
                context.fill(x, y, x + 3, y + toastH, borderCol);
                context.fill(x + 3, y, x + TOAST_WIDTH, y + 1, argb((int)(alpha * 80), 255, 255, 255));

                int tx = x + TOAST_PADDING + 3;
                int ty = y + TOAST_PADDING;

                int titleColor = toast.kind == Toast.Kind.PLAYER
                    ? argb(a, 210, 160, 255)
                    : argb(a, 255, 200, 100);

                context.drawText(tr, toast.title, tx, ty, titleColor, false);
                ty += lineH;

                context.drawText(tr, toast.line2, tx, ty, argb(a, 230, 230, 230), false);
                ty += lineH;

                if (hasLine3) {
                    context.drawText(tr, toast.line3, tx, ty, argb(a, 160, 160, 160), false);
                }

                yOffset += toastH + TOAST_GAP;
            }
        }
    }

    public void clear() {
        synchronized (toasts) {
            toasts.clear();
        }
    }

    private float computeAlpha(long ageMs) {
        if (ageMs < FADE_IN_MS) {
            return (float) ageMs / FADE_IN_MS;
        } else if (ageMs < FADE_IN_MS + HOLD_MS) {
            return 1f;
        } else {
            long fadeAge = ageMs - FADE_IN_MS - HOLD_MS;
            return Math.max(0f, 1f - (float) fadeAge / FADE_OUT_MS);
        }
    }

    private int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
