package com.arcadia.adminpanel.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * Draws the screenshare overlay on a frozen player's screen.
 *
 * <p>A frozen player who is only told in chat scrolls past it, assumes the server is lagging, and
 * relogs. Dimming the screen and pinning the reason to the middle of it removes that ambiguity
 * completely, which is the whole point of freezing someone.</p>
 *
 * <p>The overlay is drawn only while the flag is set, so an unfrozen player pays a single boolean
 * read per frame.</p>
 *
 * @author vyrriox
 */
@EventBusSubscriber(modid = "arcadiaadminpanel", value = Dist.CLIENT)
public final class FreezeOverlay {

    /** Dark wash over the world. Deliberately not opaque: staff need the player able to look around. */
    private static final int DIM_COLOR = 0xB0000000;
    private static final int TITLE_COLOR = 0xFF5FD7FF;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int HINT_COLOR = 0xFFBBBBBB;

    private FreezeOverlay() {}

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!ClientFreezeState.isFrozen()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;

        GuiGraphics g = event.getGuiGraphics();
        int width = g.guiWidth();
        int height = g.guiHeight();

        g.fill(0, 0, width, height, DIM_COLOR);

        Font font = mc.font;
        int centerX = width / 2;
        int y = height / 2 - 28;

        drawCentered(g, font, Component.translatable("arcadiaadminpanel.freeze.overlay.title"),
                centerX, y, TITLE_COLOR);
        drawCentered(g, font, Component.translatable("arcadiaadminpanel.freeze.overlay.line1"),
                centerX, y + 18, TEXT_COLOR);
        drawCentered(g, font, Component.translatable("arcadiaadminpanel.freeze.overlay.line2"),
                centerX, y + 30, TEXT_COLOR);

        long seconds = (System.currentTimeMillis() - ClientFreezeState.frozenSince()) / 1000L;
        if (ClientFreezeState.frozenSince() > 0L) {
            drawCentered(g, font, Component.translatable("arcadiaadminpanel.freeze.overlay.elapsed",
                    formatDuration(seconds)), centerX, y + 48, HINT_COLOR);
        }
    }

    private static void drawCentered(GuiGraphics g, Font font, Component text, int centerX, int y, int color) {
        g.drawString(font, text, centerX - font.width(text) / 2, y, color, true);
    }

    private static String formatDuration(long seconds) {
        long m = seconds / 60L;
        long s = seconds % 60L;
        return m > 0 ? m + "m " + s + "s" : s + "s";
    }

    /** A relog must not leave a stale overlay pinned to the screen. */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientFreezeState.clear();
    }
}
