package com.mobmusic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * What the browser extension tells the game about the song that is playing:
 * the "now playing" text, the current lyric line and the audio levels for the visualizer.
 *
 * The extension sends it to POST /push (see LocalServer); the overlay (MediaOverlay) reads it
 * when it draws. Written by the HTTP thread, read by the render thread, so every field is
 * volatile and the level array is always replaced as a whole, never modified in place.
 */
public class MediaState {

    private static final int MAX_BARS = 64;
    private static final int MAX_TEXT = 300;

    private static volatile String title = "";
    private static volatile String artist = "";
    private static volatile long nowAtMs = 0;

    private static volatile String lyric = "";
    private static volatile boolean gotLyric = false;

    /** Short messages about what is going on (shown in chat when debugChat is on). */
    private static final ConcurrentLinkedQueue<String> messages = new ConcurrentLinkedQueue<>();

    private static volatile int[] levels = new int[0];
    private static volatile long levelsAtMs = 0;

    /** Forgets everything. Called whenever the command changes (new song, pause, resume). */
    public static void clear() {
        title = "";
        artist = "";
        nowAtMs = 0;
        lyric = "";
        gotLyric = false;
        levels = new int[0];
        levelsAtMs = 0;
    }

    public static void note(String text) {
        if (messages.size() < 20) {
            messages.add(text);
        }
    }

    /** The next pending message, or null. */
    public static String pollMessage() {
        return messages.poll();
    }

    /**
     * Applies one message from the extension. Messages for an old command (the song changed or
     * stopped in the meantime) are ignored.
     *
     * {"v":12, "now":{"title":"..","artist":".."}, "lyric":"..", "b":[0..255, ...]}
     * Every field except "v" is optional.
     */
    public static void applyPush(String json) {
        JsonObject root = new JsonParser().parse(json).getAsJsonObject();
        if (!root.has("v") || root.get("v").getAsInt() != MusicState.getVersion()) {
            return;
        }

        statusFrom(root);

        JsonElement now = root.get("now");
        if (now != null && now.isJsonObject()) {
            JsonObject o = now.getAsJsonObject();
            title = text(o.get("title"));
            artist = text(o.get("artist"));
            nowAtMs = System.currentTimeMillis();
        }

        JsonElement line = root.get("lyric");
        if (line != null && line.isJsonPrimitive()) {
            lyric = text(line);
            if (!lyric.isEmpty() && !gotLyric) {
                gotLyric = true;
                note("game: first lyric line received");
            }
        }

        JsonElement bars = root.get("b");
        if (bars != null && bars.isJsonArray()) {
            JsonArray array = bars.getAsJsonArray();
            int n = Math.min(array.size(), MAX_BARS);
            int[] copy = new int[n];
            for (int i = 0; i < n; i++) {
                int v = array.get(i).getAsInt();
                copy[i] = Math.max(0, Math.min(255, v));
            }
            if (levels.length == 0 && n > 0) {
                note("game: audio levels are arriving (" + n + " bars)");
            }
            levels = copy;
            levelsAtMs = System.currentTimeMillis();
        }
    }

    private static void statusFrom(JsonObject root) {
        JsonElement status = root.get("status");
        if (status != null && status.isJsonPrimitive()) {
            note("extension: " + text(status));
        }
    }

    /**
     * Cleans a text that comes from outside: no formatting codes (the game uses the section
     * sign for colors), no control characters, and a limited length.
     */
    private static String text(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return "";
        }
        String s = element.getAsString()
                .replace("\u00a7", "")
                .replaceAll("[\\p{Cntrl}]", " ")
                .trim();
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
    }

    public static String getTitle() { return title; }
    public static String getArtist() { return artist; }
    public static long getNowAtMs() { return nowAtMs; }
    public static String getLyric() { return lyric; }
    public static int[] getLevels() { return levels; }
    public static long getLevelsAtMs() { return levelsAtMs; }
}
