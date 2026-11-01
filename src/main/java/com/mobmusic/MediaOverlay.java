package com.mobmusic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;

/**
 * Draws what the browser extension reports about the song, on top of the game:
 *
 *  - the audio visualizer (bars), BEHIND the normal HUD so it never covers the hotbar,
 *  - the lyrics as subtitles,
 *  - a short "song - artist" message when a song starts.
 *
 * Everything is semi transparent and fades in and out. Nothing is drawn unless a song that the
 * mod started is confirmed as playing.
 */
@SideOnly(Side.CLIENT)
public class MediaOverlay {

    private static final long NOW_PLAYING_TOTAL_MS = 5000;
    private static final long NOW_PLAYING_FADE_IN_MS = 300;
    private static final long NOW_PLAYING_FADE_OUT_MS = 700;

    /** If no levels arrive for this long, the bars fall to zero. */
    private static final long LEVELS_STALE_MS = 350;

    private long lastFrameMs = 0;
    private float dt = 16;               // milliseconds since the previous frame
    private boolean active = false;      // a song started by the mod is playing

    // Visualizer
    private float[] smooth = new float[0];
    private float visAlpha = 0f;

    // Lyrics
    private String shownLyric = "";
    private float lyricAlpha = 0f;
    private String wrappedFor = null;
    private int wrappedWidth = -1;
    private List<String> wrapped = null;

    /** First thing of the frame: the visualizer is drawn here, behind the rest of the HUD. */
    @SubscribeEvent
    public void onPre(RenderGameOverlayEvent.Pre e) {
        if (e.getType() != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        updateFrame();

        if (!MobMusicConfig.VISUALIZER) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        // Make sure the 2D (GUI) projection is active, whatever was drawn before.
        mc.entityRenderer.setupOverlayRendering();
        drawVisualizer(e.getResolution());
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    /** Last thing of the frame: texts go on top of everything. */
    @SubscribeEvent
    public void onPost(RenderGameOverlayEvent.Post e) {
        if (e.getType() != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        ScaledResolution res = e.getResolution();
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;

        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        drawLyric(res, font);
        drawNowPlaying(res, font);
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    // ---------------------------------------------------------------- per frame state

    private void updateFrame() {
        long now = System.currentTimeMillis();
        dt = lastFrameMs == 0 ? 16 : Math.max(1, Math.min(200, now - lastFrameMs));
        lastFrameMs = now;

        active = MusicState.getCommand().equals("play")
                && MusicState.getReport().equals("playing");

        // Messages about lyrics / visualizer: shown in chat only while debugChat is on.
        String message;
        while ((message = MediaState.pollMessage()) != null) {
            EntityPlayerSP player = Minecraft.getMinecraft().player;
            if (MobMusicConfig.DEBUG_CHAT && player != null) {
                player.sendMessage(new TextComponentString("[MobMusic] " + message));
            }
        }
    }

    /** Moves {@code current} towards {@code target}; {@code tauMs} is how slow it is. */
    private float approach(float current, float target, float tauMs) {
        float k = 1f - (float) Math.exp(-dt / tauMs);
        return current + (target - current) * k;
    }

    // ---------------------------------------------------------------- visualizer

    private void drawVisualizer(ScaledResolution res) {
        int[] raw = MediaState.getLevels();
        int n = raw.length;
        long now = System.currentTimeMillis();
        boolean fresh = n > 0 && now - MediaState.getLevelsAtMs() < 1500;

        visAlpha = approach(visAlpha, (active && fresh) ? 1f : 0f, 200f);
        if (n == 0 || visAlpha < 0.01f) {
            return;
        }

        if (smooth.length != n) {
            smooth = new float[n];
        }
        boolean stale = now - MediaState.getLevelsAtMs() > LEVELS_STALE_MS;
        for (int i = 0; i < n; i++) {
            float target = stale ? 0f : Math.min(1f, raw[i] / 255f * (float) MobMusicConfig.VIS_SENSITIVITY);
            smooth[i] = approach(smooth[i], target, target > smooth[i] ? 35f : 140f);
        }

        int alpha = (int) (255f * (float) MobMusicConfig.VIS_OPACITY * visAlpha);
        if (alpha < 4) {
            return;
        }
        alpha = Math.min(alpha, 255);

        int w = res.getScaledWidth();
        int h = res.getScaledHeight();
        int maxHeight = MobMusicConfig.VIS_HEIGHT;
        boolean top = "TOP".equals(MobMusicConfig.VIS_POSITION);
        boolean white = "WHITE".equals(MobMusicConfig.VIS_COLOR);

        int areaW = Math.max(40, (int) (w * (MobMusicConfig.VIS_WIDTH_PERCENT / 100.0)));
        int left = (w - areaW) / 2;
        float gap = 1f;
        float barW = (areaW - (n - 1) * gap) / n;

        for (int i = 0; i < n; i++) {
            float level = smooth[i];
            if (level < 0.02f) {
                continue;
            }
            int barH = Math.max(1, Math.round(level * maxHeight));
            int x0 = left + Math.round(i * (barW + gap));
            int x1 = left + Math.round(i * (barW + gap) + barW);
            if (x1 <= x0) {
                x1 = x0 + 1;
            }
            int y0 = top ? 0 : h - barH;
            int y1 = top ? barH : h;
            int rgb = white ? 0xFFFFFF : hue(n <= 1 ? 0f : (float) i / (n - 1) * 0.75f);
            Gui.drawRect(x0, y0, x1, y1, (alpha << 24) | rgb);
        }
    }

    /** Fully saturated color for a hue between 0 and 1 (0 = red, 0.33 = green, 0.66 = blue). */
    private static int hue(float h) {
        float x = (h % 1f) * 6f;
        int sector = (int) x;
        float f = x - sector;
        float r, g, b;
        switch (sector) {
            case 0:  r = 1f;     g = f;      b = 0f;     break;
            case 1:  r = 1f - f; g = 1f;     b = 0f;     break;
            case 2:  r = 0f;     g = 1f;     b = f;      break;
            case 3:  r = 0f;     g = 1f - f; b = 1f;     break;
            case 4:  r = f;      g = 0f;     b = 1f;     break;
            default: r = 1f;     g = 0f;     b = 1f - f; break;
        }
        return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }

    // ---------------------------------------------------------------- lyrics

    private static boolean lyricsAtTop() {
        return "TOP".equals(MobMusicConfig.LYRICS_POSITION);
    }

    /** Center of the lyrics: the middle of the screen plus the configured shift, kept on screen. */
    private static int clampCenter(int screenWidth, int blockWidth) {
        int half = Math.min(blockWidth / 2, screenWidth / 2);
        int center = screenWidth / 2 + MobMusicConfig.LYRICS_OFFSET_X;
        return Math.max(half, Math.min(screenWidth - half, center));
    }

    private void drawLyric(ScaledResolution res, FontRenderer font) {
        String text = (MobMusicConfig.SHOW_LYRICS && active) ? MediaState.getLyric() : "";

        if (!text.isEmpty()) {
            if (!text.equals(shownLyric)) {
                shownLyric = text;
                lyricAlpha = Math.min(lyricAlpha, 0.15f); // quick fade-in for every new line
            }
            lyricAlpha = approach(lyricAlpha, 1f, 70f);
        } else {
            lyricAlpha = approach(lyricAlpha, 0f, 150f);
        }

        if (shownLyric.isEmpty() || lyricAlpha < 0.03f) {
            return;
        }

        int w = res.getScaledWidth();
        int h = res.getScaledHeight();
        int maxWidth = (int) Math.min(w * 0.7, 420);

        if (!shownLyric.equals(wrappedFor) || maxWidth != wrappedWidth) {
            wrapped = font.listFormattedStringToWidth(shownLyric, maxWidth);
            wrappedFor = shownLyric;
            wrappedWidth = maxWidth;
        }

        int lineHeight = font.FONT_HEIGHT + 2;
        int widest = 0;
        for (String line : wrapped) {
            widest = Math.max(widest, font.getStringWidth(line));
        }
        int blockHeight = wrapped.size() * lineHeight;
        int top = lyricsAtTop() ? MobMusicConfig.LYRICS_BOTTOM_OFFSET
                : h - MobMusicConfig.LYRICS_BOTTOM_OFFSET - blockHeight;
        int bottom = top + blockHeight;
        int centerX = clampCenter(w, widest + 10);

        int textAlpha = (int) (255f * lyricAlpha);
        int bgAlpha = (int) (110f * lyricAlpha);
        if (textAlpha < 8) {
            return;
        }

        if (bgAlpha >= 4) {
            Gui.drawRect(centerX - widest / 2 - 5, top - 3, centerX + widest / 2 + 5, bottom + 1,
                    bgAlpha << 24);
        }
        int y = top;
        for (String line : wrapped) {
            font.drawStringWithShadow(line, centerX - font.getStringWidth(line) / 2f, y,
                    (textAlpha << 24) | 0xFFFFFF);
            y += lineHeight;
        }
    }

    // ---------------------------------------------------------------- "now playing"

    private void drawNowPlaying(ScaledResolution res, FontRenderer font) {
        if (!MobMusicConfig.SHOW_NOW_PLAYING || !active) {
            return;
        }
        long shownAt = MediaState.getNowAtMs();
        String title = MediaState.getTitle();
        if (shownAt == 0 || title.isEmpty()) {
            return;
        }
        long since = System.currentTimeMillis() - shownAt;
        if (since < 0 || since >= NOW_PLAYING_TOTAL_MS) {
            return;
        }

        float alpha = 1f;
        if (since < NOW_PLAYING_FADE_IN_MS) {
            alpha = since / (float) NOW_PLAYING_FADE_IN_MS;
        } else if (since > NOW_PLAYING_TOTAL_MS - NOW_PLAYING_FADE_OUT_MS) {
            alpha = (NOW_PLAYING_TOTAL_MS - since) / (float) NOW_PLAYING_FADE_OUT_MS;
        }
        int textAlpha = (int) (255f * alpha);
        if (textAlpha < 8) {
            return;
        }

        String artist = MediaState.getArtist();
        String text = "\u266A " + title + (artist.isEmpty() ? "" : " - " + artist);
        int w = res.getScaledWidth();
        int h = res.getScaledHeight();
        text = font.trimStringToWidth(text, (int) (w * 0.8));

        // It follows the lyrics: above them when they are at the bottom, below them when at the top.
        int textW = font.getStringWidth(text);
        int y = lyricsAtTop() ? MobMusicConfig.LYRICS_BOTTOM_OFFSET + 34
                : h - MobMusicConfig.LYRICS_BOTTOM_OFFSET - 30;
        int centerX = clampCenter(w, textW + 10);
        Gui.drawRect(centerX - textW / 2 - 5, y - 3, centerX + textW / 2 + 5, y + font.FONT_HEIGHT + 2,
                ((int) (110f * alpha)) << 24);
        font.drawStringWithShadow(text, centerX - textW / 2f, y, (textAlpha << 24) | 0xFFFFFF);
    }
}
