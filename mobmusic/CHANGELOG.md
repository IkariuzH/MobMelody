# Changelog

## 0.9.1
- **Visualizer after a song change.** It used to disappear when a different song (or a different boss) started,
  and only came back after reloading the YouTube Music tab. The audio capture is now renewed whenever the player's
  source or the song changes, and also when the player is playing but the capture hears 2 s of pure silence.
  Retries after a failure are faster (1.5 s).

## 0.9
- **Movable subtitles:** new `lyricsPosition` (BOTTOM / TOP), `lyricsOffsetX` (left / right) and a wider
  `lyricsBottomOffset` (up / down, 0 - 600). The "now playing" message follows the lyrics. The text never leaves the screen.
- **`visualizerSensitivity`** option (0.2 - 3.0) to make the bars taller or shorter from the game.

## 0.8
- **Visualizer fixed.** It used `createMediaElementSource()`, which a `<video>` accepts only once in its whole
  life, so after the first failure (or another extension / script touching the player) it never worked again and
  the error was hidden after the first time. It now uses `captureStream()` + `AnalyserNode`: no single-use limit,
  it reconnects when the song changes, retries after a failure, and can no longer mute the song.
- **Quick respawn fixed.** After a fast suspend + resume (dying and respawning right away) the song could stay
  paused while the extension reported "playing". The extension now checks that the player really plays after
  resuming and retries up to 4 times.
- Extension status messages (`debugChat`) are shown again for every song instead of only once per tab.
- Visualizer no longer sits at the top with loud, compressed songs: wider dB range, automatic gain and a steeper curve.
- Mod and extension now share the same version number.

## 0.6
- On-screen extras: "now playing" message, synced lyrics (LRCLIB) and audio visualizer.
- Soft pause (*suspend*) when the game is paused or the player is dead.
