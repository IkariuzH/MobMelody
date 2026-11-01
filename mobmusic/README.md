# MobMusic

Boss music from **your own YouTube Music playlist**, for Minecraft 1.12.2 (Forge).

When a mob you chose (a boss, a mini-boss, anything) shows up near you, a song from your
playlist starts. When it dies, disappears, or you leave, the song pauses. If you come back,
it **resumes where it left off**.

It can also show, on screen and in sync with the music: a "song - artist" message, the **lyrics
as subtitles**, and a see-through **audio visualizer** (see [On-screen extras](#on-screen-extras)).

No audio files, no resource packs, no sound editing. You write one line per mob:

```
minecraft:wither=own my little world
```

> **Status: hobby project, released for anyone to continue.**
> I built this for fun and because I liked the idea. I'd love for someone to take it,
> fork it, port it, or just get inspired by it. See [Ideas for contributors](#ideas-for-contributors).

## How it works

MobMusic has two small parts that talk over `127.0.0.1` (your own computer only):

```
 Minecraft + MobMusic mod                       Browser with YouTube Music
┌────────────────────────────┐   GET /state    ┌──────────────────────────┐
│ watches the mobs in your   │ <────────────── │ extension (content.js)   │
│ list, picks the highest    │                 │ polls every 0.5 s, finds │
│ priority one, writes the   │   GET /report   │ the song in your playlist│
│ current command            │ <────────────── │ and plays / pauses it    │
└────────────────────────────┘                 └──────────────────────────┘
```

The mod never touches audio. It only says *"play this title"* or *"pause"*, and the
extension clicks the play button in **your own** YouTube Music tab, with **your own** account.
Nothing is downloaded or redistributed.

While a song plays, the extension also sends the game small messages (`POST /push`): the title and
artist, the current lyric line, and the audio levels. The game only draws them.

## Requirements

- Minecraft **1.12.2** with Forge (client side only; do not put it on a server).
- A Chromium-based browser (Chrome, Edge, Brave...) with YouTube Music open on a
  **playlist page** (or with the song in the "Up next" queue).

## Install

**Mod:** put the built `.jar` in your `mods` folder.

**Extension:**
1. Open `chrome://extensions` (or `edge://extensions`).
2. Turn on *Developer mode*.
3. Click *Load unpacked* and choose the `extension/` folder.
4. Open <https://music.youtube.com> and open your playlist. Leave the tab open.

## Configuration

The file is `config/mobmusic.cfg` (also editable from the Mods menu in game; changes apply when you close the screen).

**Moving the subtitles:** `lyricsPosition` picks the edge (bottom or top), `lyricsBottomOffset` moves them up / down
from that edge, and `lyricsOffsetX` moves them left / right. Start a song, open *Mods > MobMusic > Config*, change the
numbers and close the screen to see the result.

| Option | Default | What it does |
|---|---|---|
| `mobSongs` | `minecraft:wither=YOUR_SONG_TITLE` | One `mobId=song title` per line. **The order is the priority**: the line at the top wins. |
| `muteGameWhileSongPlays` | `false` | **Set to `true` to use it.** Stops Minecraft's own music while the song plays and keeps it from starting. |
| `mutedSoundCategories` | `MUSIC` | Which sound categories are muted (`MASTER`, `MUSIC`, `RECORDS`, ...). |
| `showNowPlaying` | `true` | Shows `♪ song - artist` on screen when a song starts. |
| `showLyrics` | `false` | Lyrics as subtitles. **Off by default**: the extension asks lrclib.net for them (see below). |
| `lyricsOffsetMs` | `0` | Fine tuning. Lines too early → positive number; too late → negative. |
| `lyricsPosition` | `BOTTOM` | `BOTTOM` or `TOP`: the edge of the screen the lyrics are measured from. |
| `lyricsBottomOffset` | `80` | Up / down: distance from that edge to the lyrics (GUI pixels). |
| `lyricsOffsetX` | `0` | Left / right: shift from the center of the screen (negative = left, positive = right). |
| `visualizer` | `false` | Audio visualizer. **Off by default**: it reads the player's audio with the browser's Web Audio (see below). |
| `visualizerPosition` | `BOTTOM` | `BOTTOM` or `TOP`. |
| `visualizerColor` | `COLORS` | `COLORS` (one color per bar) or `WHITE`. |
| `visualizerOpacity` | `0.35` | 0.05 (almost invisible) to 1.0 (solid). |
| `visualizerHeight` / `visualizerWidthPercent` / `visualizerBars` | `40` / `100` / `32` | Size and number of bars. |
| `visualizerSensitivity` | `1.0` | How tall the bars get (0.2 - 3.0). Lower it if loud songs keep the bars at the top. |
| `debugChat` | `false` | Shows `[MobMusic]` messages in chat. Handy while setting things up. |
| `port` | `8765` | Local port. If you change it, change `PORT` in `extension/background.js` too. |

Tips:
- Copy the song title exactly as it appears in YouTube Music. Case and accents are ignored,
  and part of the title is enough.
- Modded mobs work out of the box: use `modid:mob_name`. To find an id, use
  `/summon modid:` + Tab, or look at the mob with Jade / WTHIT with advanced tooltips (F3+H).
- A YouTube link also works, but only for the song that is already loaded.

## On-screen extras

All of them are drawn by the mod, semi-transparent, and only while a song started by the mod is playing.

- **Now playing**: a short `♪ song - artist` message next to the lyrics (above them, or below if they are at the top).
- **Lyrics**: the extension asks [LRCLIB](https://lrclib.net) (free, no key) for the time-stamped lyrics,
  using the title, artist, album and length of the song, and shows the current line as a subtitle.
  The lyrics are copyrighted, so they are **not** in this repository: each user's browser asks for them
  at that moment and keeps them only in memory. Check LRCLIB's terms before publishing a build.
  Instrumental songs, or songs LRCLIB does not know (or knows with a different length), show nothing.
- **Visualizer**: bars drawn at the top or bottom of the screen, **behind** the normal HUD, so they never
  cover the hotbar. The extension reads the levels with a Web Audio `AnalyserNode` fed by `captureStream()` on YouTube Music's
  `<video>` and sends 20 updates per second.

## Behaviour

| Situation | What happens |
|---|---|
| A listed mob appears | The song for the highest-priority mob starts (and Minecraft music is stopped, if enabled). |
| Several listed mobs | Highest priority in the list wins. When it leaves, the next one takes over. |
| Mob dies / despawns / goes out of range | After about 2 s the song pauses and the game music comes back. |
| You open the pause menu (single player) | The song pauses. It resumes when you close the menu. |
| You die | The song pauses. If the mob is still there when you respawn, it resumes where it left off. |
| You change dimension or leave the world | The song pauses and everything is cleared. |
| The extension is closed, or the song is not found | Nothing breaks: the game music comes back after a few seconds. |

"Out of range" is whatever distance Minecraft itself keeps the mob loaded for. There is no
extra distance setting on purpose: it is meant for bosses, which are loaded from far away.
In multiplayer the game does not pause, so neither does the music when you open the menu.

## Project layout

```
src/main/java/com/mobmusic/
  MobMusic.java            entry point (Forge @Mod)
  MobMusicConfig.java      reads the config
  MobTracker.java          watches mobs, picks by priority, handles pause / death
  MusicState.java          the "order board": play / pause / suspend + version number
  LocalServer.java         tiny HTTP server on 127.0.0.1 (/state, /report and /push)
  GameMuter.java           cancels / stops Minecraft's own sounds of the chosen categories while the song plays
  MediaState.java          what the extension sent: now playing, lyric line, audio levels
  MediaOverlay.java        draws the visualizer, the subtitles and the "now playing" message
  MobMusicConfigGui.java, MobMusicGuiFactory.java   in-game config screen
extension/
  manifest.json
  background.js            service worker: the only part that talks to 127.0.0.1 and to lrclib.net
  content.js               asks background.js for orders, finds and plays the song on the YouTube Music page,
                           and feeds the game with song info, lyrics and audio levels
```

### Protocol (mod <-> extension)

`GET /state` returns:

```json
{"version": 12, "command": "play", "song": "own my little world", "mob": "minecraft:wither"}
```

`command` is `"play"` or `"pause"`. `version` goes up with every new command.
The extension answers with `GET /report?version=12&state=playing`, where `state` is
`playing`, `paused` or `failed`. A report for an old version is ignored.

Since 0.6, `/state` also carries `suspended` (a pause that is only temporary), and the settings the
extension needs: `lyrics`, `lyricsOffsetMs`, `visualizer`, `bars`.

The extension sends data to the game with `POST /push` (header `X-MobMusic: 1` required; a web page
cannot add it without a CORS preflight, which the server never answers). Body (every field except `v`
is optional):

```json
{"v": 12, "now": {"title": "...", "artist": "..."}, "lyric": "current line", "b": [0, 120, 255, ...]}
```

`v` is the command version; messages for an old version are dropped. `b` holds the bar levels (0-255).

Internally the mod has two kinds of pause: a **hard pause** (the mob is gone) and a **soft
pause** called *suspend* (the mob is still there, but the game is paused or the player is
dead). The extension sees both as `pause`; the difference only matters to the mod, which
keeps Minecraft's music muted during a suspend so it does not flicker back in.

## Building

You need a **JDK 8**, not just a JRE (ForgeGradle 3 with Gradle 5.6.4 does not work with newer JDKs).
If the build stops with `Could not find tools.jar`, `JAVA_HOME` points to a JRE: point it at the JDK folder
(the one that contains `bin\javac`). Set `JAVA_HOME` and run:

```
./gradlew build          # Windows: gradlew.bat build
```

The first run downloads and decompiles Minecraft, so it takes a while and needs ~3 GB of RAM
(`gradle.properties`). The jar that works outside the dev environment is the **reobfuscated** one,
`build/reobfJar/output.jar`; rename it to `MobMusic-<version>.jar` and drop it in `mods/`.

To test in a dev client: `./gradlew runClient`.

## Known limitations

- **How muting works.** In 1.12.2 only `MASTER` has a real live volume, and changing the other categories in the game options would get saved to `options.txt`. So the mod cancels new sounds of the muted categories and stops the ones already playing instead. Nothing is written to `options.txt`. The consequence: after the fight, the game's music does not resume mid-track, it comes back on Minecraft's own schedule (it can take a few minutes).

- Only Minecraft 1.12.2 / Forge.
- The extension relies on YouTube Music's page structure and on the labels of its play
  buttons (`content.js` has English and Spanish ones). YouTube can change either at any time;
  the selectors at the top of `content.js` are the most fragile part of the project.
- The song must be visible in the playlist page or in the queue, or already loaded.
- **Possible delay with the tab in the background.** `content.js` polls with `setTimeout`, and Chromium
  throttles timers of hidden, silent tabs (after a few minutes it can drop to about one check per minute).
  This has not been tested at length. If the first song of a session starts late, this is the likely cause;
  see "Ideas for contributors" for a fix.
- **Visualizer.** The extension taps the `<video>` with `captureStream()` and analyses that copy; the analyser is
  never placed between the player and your speakers, so it cannot silence the song. The browser only lets Web
  Audio run after you have clicked or pressed a key on the YouTube Music tab at least once (click anywhere on it
  after opening the tab). It needs a browser with `captureStream()` (Chrome, Edge, Brave) and a player that is not
  DRM-protected. If the bars never appear, set `debugChat=true` and read the `[MobMusic] extension: ...` messages
  in chat; they say what failed. It reconnects by itself when the song changes. Chromium throttles hidden tabs,
  but a tab that is playing sound is normally spared.
- **Lyrics** depend on LRCLIB having a synced version with (almost) the same length as the version you play.
  YouTube Music videos and album tracks often differ by a few seconds; in that case nothing is shown.
- With YouTube Music free (with ads), an ad can play in the middle of a fight.
- `/report` is a plain GET, so any web page in your browser could send a fake report to
  `127.0.0.1`. The worst it can do is confuse the muting. Using POST plus a custom header
  would close that.
- The author field (`title|author`) is not parsed yet, so two songs with the same title
  can be confused.

## Ideas for contributors

- **Port it** to modern Minecraft (Fabric / NeoForge). This is probably the most useful thing.
- Parse an optional author: `minecraft:wither=title|author`.
- Word-by-word (karaoke) lyrics; other lyric sources besides LRCLIB.
- Visualizer styles (mirrored, circular, peaks that fall slowly).
- Optional per-mob settings: `radius`, `volume`, `priority`.
- Wildcards (`alexsmobs:*=...`) and entity tags.
- A `/mobmusic id` command that prints the id of the mob you are looking at.
- Optional distance check with two radii (start / stop) to avoid flicker at the edge.
- Pause the song when the Minecraft window loses focus, if the user wants that.
- Support other players: Firefox, Spotify Web, or any site that has a `<video>`/`<audio>` element.
- Harden the local server (POST, token, `Access-Control-Allow-Private-Network`).
- Replace the 0.5 s polling with long-polling (the server holds `/state` open until the version changes), so timer throttling in background tabs stops mattering.
- Automated tests for `MusicState` and for the extension's song matching.

## License

[MIT](LICENSE). Do whatever you want with it. A mention is nice but not required.
