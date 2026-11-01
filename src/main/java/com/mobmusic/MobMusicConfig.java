package com.mobmusic;

import net.minecraft.util.SoundCategory;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MobMusicConfig {

    public static final String CATEGORY = "general";

    private static final String[] DEFAULTS = new String[] {
            "minecraft:wither=YOUR_SONG_TITLE"
    };

    /** One line of the list: which mob, and which song it gets. */
    public static class Entry {
        public final String mobId;
        public final String song;

        public Entry(String mobId, String song) {
            this.mobId = mobId;
            this.song = song;
        }
    }

    /** In file order: the first entry has the highest priority. */
    public static final List<Entry> ENTRIES = new ArrayList<>();

    /** Sound categories that are muted while a song plays. */
    public static final List<SoundCategory> MUTED_CATEGORIES = new ArrayList<>();

    public static int PORT = 8765;
    public static boolean DEBUG_CHAT = false;
    public static boolean MUTE_GAME = false;

    // --- On-screen extras (0.6) ---
    public static boolean SHOW_NOW_PLAYING = true;
    public static boolean SHOW_LYRICS = false;
    public static int LYRICS_OFFSET_MS = 0;
    public static int LYRICS_BOTTOM_OFFSET = 80;   // distance from the edge chosen in LYRICS_POSITION
    public static String LYRICS_POSITION = "BOTTOM";
    public static int LYRICS_OFFSET_X = 0;

    public static boolean VISUALIZER = false;
    public static String VIS_POSITION = "BOTTOM";
    public static String VIS_COLOR = "COLORS";
    public static double VIS_OPACITY = 0.35;
    public static int VIS_HEIGHT = 40;
    public static int VIS_WIDTH_PERCENT = 100;
    public static int VIS_BARS = 32;
    public static double VIS_SENSITIVITY = 1.0;

    /** Forge's object that reads and saves the file; the config screen needs it. */
    public static Configuration config;

    public static void load(File file) {
        config = new Configuration(file);
        sync();
    }

    /** Reads the values from the file (or from the config screen) into the static fields. */
    public static void sync() {
        Property port = config.get(CATEGORY, "port", 8765,
                "Local port the browser extension uses to read orders from the game.\n"
                        + "It is only reachable from this computer.\n"
                        + "If you change it, also change the same port in the extension (background.js)\n"
                        + "and restart the game.",
                1024, 65535);
        port.setRequiresMcRestart(true);
        PORT = port.getInt();

        Property debug = config.get(CATEGORY, "debugChat", false,
                "Show [MobMusic] messages in the in-game chat whenever a song is requested or stopped.\n"
                        + "Useful for testing.");
        DEBUG_CHAT = debug.getBoolean();

        Property mute = config.get(CATEGORY, "muteGameWhileSongPlays", false,
                "While a song from YouTube Music is playing, set the volume of the sound categories\n"
                        + "listed in mutedSoundCategories to 0. When the song stops, the volume goes back\n"
                        + "to the value you have in the game options (nothing is written to options.txt).\n"
                        + "If the browser extension does not confirm the song, the sound comes back by itself.");
        MUTE_GAME = mute.getBoolean();

        Property categories = config.get(CATEGORY, "mutedSoundCategories",
                new String[] { "MUSIC" },
                "Sound categories that are muted while a song plays (one per line).\n"
                        + "Valid values: MASTER, MUSIC, RECORDS, WEATHER, BLOCKS, HOSTILE, NEUTRAL,\n"
                        + "PLAYERS, AMBIENT, VOICE.\n"
                        + "MUSIC mutes only the music of the game (default).\n"
                        + "MASTER mutes everything, including mob and combat sounds.");
        MUTED_CATEGORIES.clear();
        for (String name : categories.getStringList()) {
            String clean = name.trim().toUpperCase(Locale.ROOT);
            if (clean.isEmpty()) {
                continue;
            }
            try {
                MUTED_CATEGORIES.add(SoundCategory.valueOf(clean));
            } catch (IllegalArgumentException e) {
                MobMusic.LOGGER.warn("Unknown sound category: " + name);
            }
        }

        Property nowPlaying = config.get(CATEGORY, "showNowPlaying", true,
                "Show a short \"song - artist\" message on screen when a song starts.");
        SHOW_NOW_PLAYING = nowPlaying.getBoolean();

        Property lyrics = config.get(CATEGORY, "showLyrics", false,
                "Show the lyrics of the song as subtitles, in sync with the music.\n"
                        + "OFF by default because it makes the browser extension ask lrclib.net for the lyrics\n"
                        + "(it sends the title, artist, album and length of the song). The lyrics are only kept\n"
                        + "in memory while the browser tab is open; nothing is saved to disk.\n"
                        + "Instrumental songs and songs without synced lyrics simply show nothing.");
        SHOW_LYRICS = lyrics.getBoolean();

        Property offset = config.get(CATEGORY, "lyricsOffsetMs", 0,
                "Fine tuning for the lyrics, in milliseconds. If the lines show up too EARLY, use a\n"
                        + "positive number; if they show up too LATE, use a negative one.",
                -5000, 5000);
        LYRICS_OFFSET_MS = offset.getInt();

        Property lyricsY = config.get(CATEGORY, "lyricsBottomOffset", 80,
                "Vertical position of the lyrics: distance in pixels (GUI scale) from the edge of the\n"
                        + "screen chosen in lyricsPosition (the bottom by default).\n"
                        + "The default (80 at the bottom) sits above the health bars and the item name.",
                0, 600);
        LYRICS_BOTTOM_OFFSET = lyricsY.getInt();

        LYRICS_POSITION = config.get(CATEGORY, "lyricsPosition", "BOTTOM",
                "Which edge the lyrics are measured from: BOTTOM or TOP of the screen.\n"
                        + "Use it together with lyricsBottomOffset (up / down) and lyricsOffsetX (left / right).\n"
                        + "The \"now playing\" message follows the lyrics.",
                new String[] { "BOTTOM", "TOP" }).getString();

        LYRICS_OFFSET_X = config.get(CATEGORY, "lyricsOffsetX", 0,
                "Horizontal position of the lyrics, in pixels (GUI scale), from the center of the screen.\n"
                        + "Negative = to the left, positive = to the right. The text never leaves the screen.",
                -1000, 1000).getInt();

        Property vis = config.get(CATEGORY, "visualizer", false,
                "Draw an audio visualizer (bars that move with the music) on the screen.\n"
                        + "OFF by default: to read the sound, the extension taps the audio of the YouTube Music\n"
                        + "player with the browser's Web Audio (it never sits between the player and your\n"
                        + "speakers). The first time, click once anywhere on the YouTube Music tab.\n"
                        + "If something goes wrong, turn this off and reload the YouTube Music tab.");
        VISUALIZER = vis.getBoolean();

        VIS_POSITION = config.get(CATEGORY, "visualizerPosition", "BOTTOM",
                "Where the visualizer is drawn: BOTTOM or TOP of the screen.",
                new String[] { "BOTTOM", "TOP" }).getString();

        VIS_COLOR = config.get(CATEGORY, "visualizerColor", "COLORS",
                "COLORS = a different color for each bar. WHITE = all bars white.",
                new String[] { "COLORS", "WHITE" }).getString();

        VIS_OPACITY = config.get(CATEGORY, "visualizerOpacity", 0.35,
                "How see-through the bars are: 0.05 = almost invisible, 1.0 = solid.",
                0.05, 1.0).getDouble();

        VIS_HEIGHT = config.get(CATEGORY, "visualizerHeight", 40,
                "Maximum height of the bars, in pixels (GUI scale).", 10, 200).getInt();

        VIS_WIDTH_PERCENT = config.get(CATEGORY, "visualizerWidthPercent", 100,
                "Width of the visualizer as a percentage of the screen width (centered).",
                10, 100).getInt();

        VIS_BARS = config.get(CATEGORY, "visualizerBars", 32,
                "Number of bars.", 8, 64).getInt();

        VIS_SENSITIVITY = config.get(CATEGORY, "visualizerSensitivity", 1.0,
                "How tall the bars get. 1.0 = normal. Lower it (0.5) if the bars sit at the top with loud\n"
                        + "songs, raise it (1.5) if they look too low with quiet ones.",
                0.2, 3.0).getDouble();

        Property list = config.get(CATEGORY, "mobSongs", DEFAULTS,
                "One entry per line, written as <mob id>=<song>.\n"
                        + "The ORDER is the PRIORITY: the line at the top wins over the lines below it.\n"
                        + "<mob id> is the entity id, for example minecraft:wither (modded mobs use modid:name).\n"
                        + "<song> is the TITLE of a song in the YouTube Music playlist that is playing in your\n"
                        + "browser (recommended; case and accents are ignored and part of the title is enough).\n"
                        + "A YouTube Music link only works for the song that is already loaded.");

        ENTRIES.clear();
        for (String line : list.getStringList()) {
            String text = line.trim();
            if (text.isEmpty()) {
                continue;
            }

            // Split at the FIRST '=': YouTube links contain another '=' (watch?v=...)
            int equals = text.indexOf('=');
            if (equals <= 0) {
                MobMusic.LOGGER.warn("Line ignored (missing 'id=song'): " + text);
                continue;
            }

            String mobId = text.substring(0, equals).trim();
            String song = text.substring(equals + 1).trim();

            if (song.isEmpty()) {
                MobMusic.LOGGER.warn("Line ignored (empty song): " + text);
                continue;
            }
            if (isListed(mobId)) {
                MobMusic.LOGGER.warn("Duplicate mob, the first one wins: " + mobId);
                continue;
            }
            ENTRIES.add(new Entry(mobId, song));
        }

        if (config.hasChanged()) {
            config.save();
        }
        MobMusic.LOGGER.info("Entries loaded: " + ENTRIES.size());
    }

    public static boolean isListed(String mobId) {
        for (Entry e : ENTRIES) {
            if (e.mobId.equals(mobId)) {
                return true;
            }
        }
        return false;
    }
}
