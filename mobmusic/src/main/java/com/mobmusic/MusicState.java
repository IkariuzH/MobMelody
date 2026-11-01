package com.mobmusic;

/**
 * The shared "order board" between the game and the browser extension.
 *
 * The game writes the current command ("play" or "pause"); the extension polls it
 * through LocalServer and answers with a report ("playing", "paused" or "failed").
 * Every new command bumps {@code version}, so a late answer to an old command is ignored.
 *
 * There are two kinds of pause:
 *  - pause():   hard stop. The mob is gone (or the world was closed).
 *  - suspend(): soft pause. The mob is still around, but the game is paused or the
 *               player is dead. The mod will send play() again when that ends, and the
 *               extension resumes the song where it left off.
 */
public class MusicState {

    private static String command = "pause";
    private static String song = "";
    private static String mob = "";
    private static boolean suspended = false;
    private static int version = 0;
    private static long changedAtMs = 0;

    // Last report from the extension: which command it answers, and what it said
    private static int reportVersion = -1;
    private static String reportState = "";

    /** The game asks for a song to play (or to resume). */
    public static synchronized void play(String newSong, String newMob) {
        command = "play";
        song = newSong;
        mob = newMob;
        suspended = false;
        version++;
        changedAtMs = System.currentTimeMillis();
        MediaState.clear();
    }

    /** Hard stop. Does nothing if the music is already fully stopped. */
    public static synchronized void pause() {
        if (command.equals("pause") && !suspended) {
            return;
        }
        command = "pause";
        song = "";
        mob = "";
        suspended = false;
        version++;
        changedAtMs = System.currentTimeMillis();
        MediaState.clear();
    }

    /** Soft pause (game paused / player dead). Does nothing if already paused or suspended. */
    public static synchronized void suspend() {
        if (command.equals("pause")) {
            return;
        }
        command = "pause";
        song = "";
        mob = "";
        suspended = true;
        version++;
        changedAtMs = System.currentTimeMillis();
        MediaState.clear();
    }

    /** The extension reports "playing", "paused" or "failed" for the command of that version. */
    public static synchronized void registerReport(int requestedVersion, String state) {
        if (requestedVersion != version) {
            return; // answer to a command that is no longer the current one
        }
        reportVersion = requestedVersion;
        reportState = state;
    }

    public static synchronized int getVersion() {
        return version;
    }

    public static synchronized String getCommand() {
        return command;
    }

    /** True while the music is paused only temporarily (the mob is still around). */
    public static synchronized boolean isSuspended() {
        return suspended;
    }

    public static synchronized long getChangedAtMs() {
        return changedAtMs;
    }

    /** The extension's report for the current command, or "" if it has not answered yet. */
    public static synchronized String getReport() {
        return reportVersion == version ? reportState : "";
    }

    /** What /state returns. */
    public static synchronized String asJson() {
        return "{\"version\":" + version
                + ",\"command\":\"" + escape(command) + "\""
                + ",\"song\":\"" + escape(song) + "\""
                + ",\"mob\":\"" + escape(mob) + "\""
                // Extra fields (0.6): the extension only reads them, older extensions ignore them.
                + ",\"suspended\":" + suspended
                + ",\"lyrics\":" + MobMusicConfig.SHOW_LYRICS
                + ",\"lyricsOffsetMs\":" + MobMusicConfig.LYRICS_OFFSET_MS
                + ",\"visualizer\":" + MobMusicConfig.VISUALIZER
                + ",\"bars\":" + MobMusicConfig.VIS_BARS
                + "}";
    }

    /** Escapes quotes, backslashes and line breaks so the JSON is never broken. */
    private static String escape(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
