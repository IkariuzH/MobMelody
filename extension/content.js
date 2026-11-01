(function () {
    "use strict";

    const POLL_MS = 500;           // normal polling
    const RETRY_MS = 5000;         // when the game is closed
    const VERIFY_WAIT_MS = 1500;   // how long we wait to see if the title changed
    const VERIFY_PAUSE_MS = 300;   // how long we wait to confirm the player is paused
    const MAX_BUTTON_CANDIDATES = 3; // how many "play buttons" we try per row

    // What we send to the game while a song plays (song info, lyrics, audio levels).
    const FEED_MS = 50;              // 20 times per second at most
    const FEED_START_DELAY_MS = 400; // wait a moment so the player bar shows the new song
    const TRACK_CHECK_MS = 500;      // how often we re-read title / artist

    // Where we look for songs: the playlist and the "Up next" queue.
    const ROW_SELECTOR =
        "ytmusic-playlist-shelf-renderer ytmusic-responsive-list-item-renderer, " +
        "ytmusic-player-queue ytmusic-player-queue-item";

    // Where the play button is inside a row.
    // NOTE: the aria-label ones depend on the language of the YouTube Music interface.
    // If yours is not English or Spanish, add the label of your language here.
    const BUTTON_SELECTORS = [
        "#play-button",
        "ytmusic-play-button-renderer",
        "ytmusic-play-button-renderer yt-icon-button",
        ".play-button",
        "ytmusic-item-thumbnail-overlay-renderer",
        "[aria-label^='Reproducir']",
        "[aria-label^='Play']"
    ];

    let lastVersion = null;        // null = we have no reference from the game yet
    let gameConnected = false;
    let startedByMod = false;      // true = the music that is playing was started by the mod
    let currentSong = null;        // config text of the song the mod started
    let commandCounter = 0;        // goes up with every command; used to abandon old attempts
    let attemptInProgress = false; // true while we try ways of switching song

    // Settings the mod sends along with every /state (they follow the in-game config).
    const settings = {
        suspended: false,
        lyrics: false,
        lyricsOffsetMs: 0,
        visualizer: false,
        bars: 32
    };

    let feedTimer = null;          // setInterval that sends data to the game
    let feedVersion = null;        // command version the feed belongs to
    let feedStartedAt = 0;
    let feedTrackKey = null;       // "title|artist" of the song we are feeding
    let currentInfo = null;        // { title, artist, album, duration }
    let announcedKey = null;       // last song we announced ("now playing")
    let lastTrackCheck = 0;
    let lastLyricText = null;      // last lyric line sent (null = nothing sent yet)
    let pushInFlight = false;
    let pendingStatus = null;      // a short message for the game's chat (shown with debugChat)
    let lastStatus = null;
    let lyricsRetryAt = 0;         // after a network error, do not ask again before this time

    let lyricsFor = null;          // cache key of the lyrics we are using
    let currentLyrics = null;      // { lines: [{ t, text }] } or null
    const lyricsCache = new Map(); // cache key -> { lines: [...] }
    const lyricsPending = new Set();

    // Audio analysis (visualizer). "idle" | "starting" | "ready" | "failed"
    let primedCtx = null;          // AudioContext created at the user's first click / key press
    let analyserState = "idle";
    let analyserRetryAt = 0;       // after a failure, do not try again before this time
    let analyserTimer = null;      // waits for the AudioContext to be running
    let analyserStream = null;     // the MediaStream captured from the <video>
    let analyserSource = null;
    let analyserVideo = null;      // the <video> the stream was captured from
    let analyserSrc = "";          // its source (currentSrc) when it was captured
    let analyserKey = null;        // the song ("title|artist") it was captured for
    let silentTicks = 0;           // consecutive readings of pure silence while the video plays
    const SILENT_TICKS_LIMIT = 40; // 40 x 50 ms = 2 s of silence on a playing video -> capture again
    let audioCtx = null;
    let analyser = null;
    let freqData = null;
    let levelRef = 0.5;            // slow-moving "how loud is this song" reference (auto gain)

    /**
     * Talks to the game through the extension's background worker (background.js).
     * The page itself is not allowed to reach 127.0.0.1 in recent browsers.
     * Returns the data, or throws if the game is not reachable.
     */
    function bridge(message) {
        return new Promise(function (resolve, reject) {
            try {
                chrome.runtime.sendMessage(message, function (response) {
                    if (chrome.runtime.lastError) {
                        reject(new Error(chrome.runtime.lastError.message));
                    } else if (!response || !response.ok) {
                        reject(new Error(response && response.error ? response.error : "no answer"));
                    } else {
                        resolve(response.data);
                    }
                });
            } catch (error) {
                // Happens when the extension was reloaded and this tab is still the old one.
                reject(error);
            }
        });
    }

    /** Tells the mod the result of a command: "playing", "paused" or "failed". */
    function report(state, result) {
        if (!state || typeof state.version !== "number") {
            return;
        }
        if (result === "playing") {
            startFeed(state); // from now on we also tell the game what is playing
        }
        bridge({ type: "report", version: state.version, state: result }).catch(function () {
            // the game may have been closed: no problem
        });
    }

    /** The page's player. */
    function getVideo() {
        return document.querySelector("video");
    }

    /** Lowercase, no accents and single spaces, to compare titles. */
    function normalize(text) {
        return (text || "")
            .toLowerCase()
            .normalize("NFD")
            .replace(/[\u0300-\u036f]/g, "")
            .replace(/\s+/g, " ")
            .trim();
    }

    /** If the text is a YouTube link, returns the video id (11 characters). */
    function extractVideoId(text) {
        const m = text.match(/[?&]v=([A-Za-z0-9_-]{11})/) ||
            text.match(/youtu\.be\/([A-Za-z0-9_-]{11})/);
        return m ? m[1] : null;
    }

    /** In the player view, the id of the loaded song is in the address. */
    function videoIdInUrl() {
        if (location.pathname !== "/watch") {
            return null;
        }
        return new URLSearchParams(location.search).get("v");
    }

    /** Title of the song loaded in the player bar (normalized). */
    function loadedTitle() {
        const bar = document.querySelector("ytmusic-player-bar .title");
        let title = bar ? bar.textContent : "";
        if (!normalize(title) && navigator.mediaSession && navigator.mediaSession.metadata) {
            title = navigator.mediaSession.metadata.title || "";
        }
        return normalize(title);
    }

    /**
     * All the song rows that are visible on screen right now (playlist and queue).
     * Hidden ones are ignored: the queue has more rows than tracks and some are not visible.
     */
    function visibleRows() {
        return Array.from(document.querySelectorAll(ROW_SELECTOR)).filter(function (row) {
            const r = row.getBoundingClientRect();
            return r.width > 0 && r.height > 0;
        });
    }

    /** Short text that describes an element in the console: tag#id.class.class */
    function describe(el) {
        let name = el.tagName.toLowerCase();
        if (el.id) {
            name += "#" + el.id;
        }
        if (typeof el.className === "string" && el.className.trim()) {
            name += "." + el.className.trim().split(/\s+/).slice(0, 2).join(".");
        }
        return name;
    }

    /** Is the requested song exactly the one already loaded in the player? */
    function isLoadedSong(song) {
        const id = extractVideoId(song);

        if (id) {
            const urlId = videoIdInUrl();
            if (urlId !== null) {
                return urlId === id; // we are in the player view
            }
            // Outside that view: compare the loaded title with the text of the row for that id
            const title = loadedTitle();
            if (!title) {
                return false;
            }
            for (const row of visibleRows()) {
                if (row.querySelector('a[href*="watch?v=' + id + '"]')) {
                    return normalize(row.innerText).includes(title);
                }
            }
            return false;
        }

        const title = loadedTitle();
        return title !== "" && title.includes(normalize(song));
    }

    /**
     * Looks for the song in the visible rows. Returns { row, target, kind } or null.
     * kind "link": the row has a watch?v= link (we click it).
     * kind "row":  the row matches by text but has no link.
     * By title it prefers a row WITH a link; if none has one, it uses the first match.
     */
    function findRow(song) {
        const rows = visibleRows();
        const id = extractVideoId(song);
        const wanted = normalize(song);
        let fallback = null;

        for (const row of rows) {
            if (id) {
                const link = row.querySelector('a[href*="watch?v=' + id + '"]');
                if (link) {
                    return { row: row, target: link, kind: "link" };
                }
            } else if (normalize(row.innerText).includes(wanted)) {
                const link = row.querySelector('a[href*="watch?v="]');
                if (link) {
                    return { row: row, target: link, kind: "link" };
                }
                if (!fallback) {
                    fallback = { row: row, target: row, kind: "row" };
                }
                // keep looking: there may be another row with a link
            }
        }
        return fallback;
    }

    /** Candidates for the "play button" inside a row, without repeats. */
    function buttonCandidates(row) {
        const list = [];
        for (const selector of BUTTON_SELECTORS) {
            const el = row.querySelector(selector);
            if (el && list.indexOf(el) === -1) {
                list.push(el);
            }
            if (list.length >= MAX_BUTTON_CANDIDATES) {
                break;
            }
        }
        return list;
    }

    /** Ways to try to play the row we found, in order. */
    function strategiesFor(found) {
        if (found.kind === "link") {
            return [{
                name: "click on the link",
                run: function () { found.target.click(); }
            }];
        }

        const list = [];
        buttonCandidates(found.row).forEach(function (button) {
            list.push({
                name: "click on " + describe(button),
                run: function () { button.click(); }
            });
        });
        list.push({
            name: "double click on the row",
            run: function () {
                found.row.dispatchEvent(
                    new MouseEvent("dblclick", { bubbles: true, cancelable: true, view: window }));
            }
        });
        return list;
    }

    /** Prints how a row is built (to help adjust the selectors). */
    function dumpRow(row) {
        if (!row) {
            return;
        }
        const inside = new Set();
        row.querySelectorAll("*").forEach(function (el) {
            inside.add(describe(el));
        });
        console.log("[MobMusic] row: tag =", row.tagName.toLowerCase(),
            "| elements inside:", Array.from(inside).join(", "));
        console.log("[MobMusic] row: HTML (trimmed):", row.outerHTML.slice(0, 3500));
    }

    /**
     * If the song is not found: writes to the console where its text appears on the
     * page (chain of elements), to learn how the page is built.
     */
    function diagnose(song) {
        const wanted = normalize(song);
        const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
        let found = 0;

        while (walker.nextNode() && found < 6) {
            const node = walker.currentNode;
            if (node.textContent.length > 300) {
                continue;
            }
            if (!normalize(node.textContent).includes(wanted)) {
                continue;
            }
            found++;

            const chain = [];
            let el = node.parentElement;
            for (let i = 0; i < 8 && el; i++) {
                chain.push(describe(el));
                el = el.parentElement;
            }
            console.log("[MobMusic] diagnosis, text found in:", chain.join("  <  "));
        }
        console.log("[MobMusic] diagnosis: places with that text:", found,
            "| rows I recognize:", visibleRows().length, "| URL:", location.pathname);
    }

    /**
     * Plays the loaded song and makes sure it really sounds. A quick pause + resume (dying and
     * respawning fast) can leave the <video> paused, or can interrupt play() with the pause, and
     * nothing noticed. So: call play(), report "playing" as soon as it works, and keep watching
     * for a moment; if the player is paused again, play() once more (up to 4 tries). A newer
     * command from the game cancels the whole thing.
     */
    function resumeVideo(state, label) {
        const myCommand = commandCounter;
        let tries = 0;

        function attempt() {
            if (myCommand !== commandCounter) {
                return; // the game sent something newer
            }
            const video = getVideo();
            if (!video) {
                report(state, "failed");
                return;
            }
            tries++;
            video.loop = true;
            if (!video.paused) {
                report(state, "playing");
                watch();
                return;
            }
            video.play()
                .then(function () {
                    if (myCommand !== commandCounter) {
                        return;
                    }
                    console.log("[MobMusic] " + label + " (try " + tries + ")");
                    report(state, "playing");
                    watch();
                })
                .catch(function (error) {
                    if (myCommand !== commandCounter) {
                        return;
                    }
                    console.warn("[MobMusic] play() failed (try " + tries + "):", error);
                    if (tries < 4) {
                        setTimeout(attempt, 250);
                    } else {
                        report(state, "failed");
                    }
                });
        }

        function watch() {
            setTimeout(function () {
                if (myCommand !== commandCounter) {
                    return;
                }
                const video = getVideo();
                if (video && (video.paused || video.ended) && tries < 4) {
                    console.warn("[MobMusic] the song stopped right after resuming, trying again");
                    attempt();
                }
            }, 700);
        }

        attempt();
    }

    /**
     * Tries the ways of playing one by one. After each one it waits a bit and checks
     * that the player bar title is now the requested one. If not, it moves on to the
     * next. If they run out, it reports and does NOT call play() (we do not want to
     * resume some other song).
     */
    function tryStrategies(found, state, myCommand) {
        const strategies = strategiesFor(found);
        attemptInProgress = true;

        function attempt(i) {
            if (myCommand !== commandCounter) {
                return; // another command arrived while we were trying: give up
            }
            if (i >= strategies.length) {
                attemptInProgress = false;
                console.warn("[MobMusic] no way of playing worked. NOT calling play() " +
                    "so another song is not resumed. Requested:", state.song);
                dumpRow(found.row);
                report(state, "failed");
                return;
            }

            const strategy = strategies[i];
            console.log("[MobMusic] attempt " + (i + 1) + "/" + strategies.length + ":",
                strategy.name);
            strategy.run();

            setTimeout(function () {
                if (myCommand !== commandCounter) {
                    return;
                }
                if (isLoadedSong(state.song)) {
                    attemptInProgress = false;
                    currentSong = state.song;
                    startedByMod = true;
                    resumeVideo(state, "song started");
                    console.log("[MobMusic] song change confirmed:", state.song);
                } else {
                    console.warn("[MobMusic] the player bar title did not change after:",
                        strategy.name, "| current title:", loadedTitle());
                    attempt(i + 1);
                }
            }, VERIFY_WAIT_MS);
        }

        attempt(0);
    }

    function onPlay(state) {
        attemptInProgress = false;   // this command replaces any earlier attempt
        const myCommand = commandCounter;

        const video = getVideo();
        if (!video) {
            console.warn("[MobMusic] there is no player on the page yet");
            report(state, "failed");
            return;
        }
        if (!state.song || !state.song.trim()) {
            console.warn("[MobMusic] this mob has no song in the config");
            report(state, "failed");
            return;
        }

        // Same song we already started and it is still playing: do nothing.
        if (state.song === currentSong && !video.paused) {
            console.log("[MobMusic] that song is already playing");
            resumeVideo(state, "already playing");
            return;
        }

        // 1) If it is already the loaded song, resume it without searching or clicking
        //    (this keeps its position).
        if (isLoadedSong(state.song)) {
            currentSong = state.song;
            startedByMod = true;
            resumeVideo(state, "resumed the loaded song (position kept)");
            return;
        }

        // 2) If it is another song, look for it (playlist or queue) and try ways of playing it.
        const found = findRow(state.song);
        if (!found) {
            console.warn("[MobMusic] I could not find the song on the page:", state.song);
            diagnose(state.song);
            report(state, "failed");
            return;
        }

        console.log("[MobMusic] playing:", state.song, "| found as:", found.kind);
        tryStrategies(found, state, myCommand);
    }


    // ------------------------------------------------------------------------------------
    // Song info, lyrics and audio levels -> game
    // ------------------------------------------------------------------------------------

    function readSettings(state) {
        settings.suspended = state.suspended === true;
        settings.lyrics = state.lyrics === true;
        settings.lyricsOffsetMs = Number(state.lyricsOffsetMs) || 0;
        settings.visualizer = state.visualizer === true;
        settings.bars = Math.max(8, Math.min(64, Number(state.bars) || 32));
    }

    /** Title, artist, album and length of the song loaded in the player. */
    function trackInfo(video) {
        const md = navigator.mediaSession && navigator.mediaSession.metadata;
        let title = (md && md.title) || "";
        let artist = (md && md.artist) || "";
        const album = (md && md.album) || "";

        if (!normalize(title)) {
            const bar = document.querySelector("ytmusic-player-bar .title");
            title = bar ? bar.textContent : "";
        }
        if (!normalize(artist)) {
            // The line under the title looks like "Artist • Album • Year"
            const byline = document.querySelector("ytmusic-player-bar .byline");
            artist = byline ? byline.textContent.split("\u2022")[0] : "";
        }
        return {
            title: title.trim(),
            artist: artist.trim(),
            album: album.trim(),
            duration: video && isFinite(video.duration) ? video.duration : 0
        };
    }

    /** Queues a short message that the game shows in chat when debugChat is on. */
    function status(text) {
        if (text !== lastStatus) {
            lastStatus = text;
            pendingStatus = text;
        }
        console.log("[MobMusic] " + text);
    }

    function trackKey(info) {
        return normalize(info.title) + "|" + normalize(info.artist);
    }

    // ---- lyrics ----

    /** Turns "[01:23.45] text" lines into a sorted list of { t: seconds, text }. */
    function parseLrc(text) {
        const lines = [];
        String(text || "").split(/\r?\n/).forEach(function (raw) {
            const head = raw.match(/^((?:\s*\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?\])+)(.*)$/);
            if (!head) {
                return; // tags like [ar:Artist] or empty lines
            }
            const words = head[2].replace(/<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>/g, "").trim();
            const stamp = /\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]/g;
            let m;
            while ((m = stamp.exec(head[1])) !== null) {
                const fraction = m[3] ? parseFloat("0." + m[3]) : 0;
                lines.push({ t: Number(m[1]) * 60 + Number(m[2]) + fraction, text: words });
            }
        });
        lines.sort(function (a, b) { return a.t - b.t; });
        return lines;
    }

    /** The text of the line that should be showing at that second ("" before the first one). */
    function lineAt(lines, seconds) {
        let lo = 0;
        let hi = lines.length - 1;
        let found = -1;
        while (lo <= hi) {
            const mid = (lo + hi) >> 1;
            if (lines[mid].t <= seconds) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found < 0 ? "" : lines[found].text;
    }

    /** Asks (through background.js) for the lyrics, once per song; the result is cached. */
    function loadLyrics(info) {
        const cacheKey = trackKey(info) + "|" + Math.round(info.duration);
        lyricsFor = cacheKey;
        if (lyricsCache.has(cacheKey)) {
            currentLyrics = lyricsCache.get(cacheKey);
            return;
        }
        currentLyrics = null;
        if (lyricsPending.has(cacheKey)) {
            return;
        }
        lyricsPending.add(cacheKey);
        bridge({
            type: "lyrics",
            title: info.title,
            artist: info.artist,
            album: info.album,
            duration: info.duration
        }).then(function (data) {
            const lines = data && data.synced ? parseLrc(data.synced) : [];
            const entry = { lines: lines };
            lyricsCache.set(cacheKey, entry); // an empty list is cached too: do not ask again
            if (lyricsFor === cacheKey) {
                currentLyrics = entry;
            }
            if (lines.length) {
                status("lyrics found: \"" + data.track + "\" - " + data.artist +
                    " (" + lines.length + " lines)");
            } else if (data && data.instrumental) {
                status("lyrics: LRCLIB says \"" + info.title + "\" is instrumental");
            } else {
                status("lyrics: nothing found on LRCLIB for \"" + info.title + "\" - " + info.artist +
                    " (" + Math.round(info.duration) + " s)");
            }
        }).catch(function (error) {
            // Network error: not cached, so it is tried again next time.
            status("lyrics: could not reach LRCLIB (" + error.message + ")");
            lyricsRetryAt = Date.now() + 30000;
            if (lyricsFor === cacheKey) {
                lyricsFor = null;
            }
        }).then(function () {
            lyricsPending.delete(cacheKey);
        });
    }

    function desiredLyric(video) {
        if (!settings.lyrics || !currentLyrics || currentLyrics.lines.length === 0) {
            return "";
        }
        // Positive offset = the lines are shown early, so we look a bit further back in time.
        return lineAt(currentLyrics.lines, video.currentTime - settings.lyricsOffsetMs / 1000);
    }

    // ---- audio analysis (visualizer) ----

    /**
     * Browsers only let an AudioContext start running after the user has clicked or pressed a key
     * on the page. The visualizer is needed when a boss appears, and by then the player is in
     * Minecraft, not in this tab. So the context is created (and started) at the first click / key
     * press on the YouTube Music tab, which happens anyway when you open your playlist. It only
     * does anything if the visualizer is turned on in the game.
     */
    function primeAudioOnFirstGesture() {
        const events = ["pointerdown", "keydown"];
        const handler = function () {
            if (!settings.visualizer || primedCtx) {
                return; // not needed (yet); keep listening in case it is turned on later
            }
            events.forEach(function (name) { document.removeEventListener(name, handler, true); });
            try {
                const Ctx = window.AudioContext || window.webkitAudioContext;
                primedCtx = new Ctx();
                primedCtx.resume().catch(function () { });
                console.log("[MobMusic] audio context prepared on user gesture");
            } catch (error) {
                console.warn("[MobMusic] could not prepare the audio context:", error);
            }
        };
        events.forEach(function (name) { document.addEventListener(name, handler, true); });
    }

    /** One AudioContext for the whole life of the tab. */
    function getContext() {
        if (!audioCtx) {
            const Ctx = window.AudioContext || window.webkitAudioContext;
            audioCtx = primedCtx || new Ctx();
        }
        return audioCtx;
    }

    /**
     * Starts the analyser (only when the visualizer is on). It waits until the AudioContext is
     * running (the browser needs a click / key press on the tab first), then connects.
     */
    function startAnalyser() {
        if (analyserState !== "idle") {
            return;
        }
        analyserState = "starting";

        let ctx;
        try {
            ctx = getContext();
        } catch (error) {
            failAnalyser(error);
            return;
        }

        const unlock = function () { ctx.resume().catch(function () { }); };
        const events = ["pointerdown", "keydown"];
        events.forEach(function (name) { document.addEventListener(name, unlock, true); });

        let waited = 0;
        clearInterval(analyserTimer);
        analyserTimer = setInterval(function () {
            if (ctx.state === "running") {
                clearInterval(analyserTimer);
                analyserTimer = null;
                events.forEach(function (name) { document.removeEventListener(name, unlock, true); });
                connectAnalyser(ctx);
                return;
            }
            unlock();
            waited += 500;
            if (waited === 3000) {
                status("visualizer waiting: click once anywhere on the YouTube Music tab " +
                    "(the browser blocks audio analysis until then)");
            }
        }, 500);
    }

    function failAnalyser(error) {
        analyserState = "failed";
        analyserRetryAt = Date.now() + 1500; // it will try again by itself
        status("visualizer unavailable: " + (error && error.message ? error.message : error));
    }

    /**
     * Reads the audio of the <video> with captureStream(). Unlike createMediaElementSource(),
     * it can be done as many times as needed (a <video> accepts only ONE media element source
     * for its whole life, which is what used to break the visualizer), and the analyser is NOT
     * placed between the video and the speakers, so it can never silence the song.
     */
    function connectAnalyser(ctx) {
        try {
            const video = getVideo();
            if (!video) {
                throw new Error("no player");
            }
            if (typeof video.captureStream !== "function") {
                throw new Error("this browser cannot capture the player audio (captureStream)");
            }
            const tracks = video.captureStream().getAudioTracks();
            if (tracks.length === 0) {
                throw new Error("the player has no audio track yet");
            }
            const stream = new MediaStream(tracks);
            const source = ctx.createMediaStreamSource(stream);
            const node = ctx.createAnalyser();
            node.fftSize = 1024;
            node.smoothingTimeConstant = 0.6;
            node.minDecibels = -85;  // default is -100 / -30: too sensitive for loud, compressed music
            node.maxDecibels = -8;
            levelRef = 0.5;
            source.connect(node); // deliberately NOT connected to ctx.destination
            analyserStream = stream;
            analyserSource = source;
            analyserVideo = video;
            analyserSrc = video.currentSrc || "";
            analyserKey = feedTrackKey;
            silentTicks = 0;
            analyser = node;
            freqData = new Uint8Array(node.frequencyBinCount);
            analyserState = "ready";
            status("visualizer ready (audio analyser connected)");
        } catch (error) {
            failAnalyser(error);
        }
    }

    /**
     * Is the capture still the right one? When YouTube Music changes song, the captured stream can
     * end, or stay "alive" but silent, so the tracks alone are not a reliable sign. We also look at
     * the player's source, at the song, and at whether the player is playing but we hear nothing.
     */
    function analyserIsHealthy() {
        const video = getVideo();
        if (!video || analyserVideo !== video || analyserStream === null) {
            return false;
        }
        if ((video.currentSrc || "") !== analyserSrc) {
            return false; // another source loaded in the same <video>
        }
        if (analyserKey && feedTrackKey && analyserKey !== feedTrackKey) {
            return false; // another song
        }
        if (silentTicks > SILENT_TICKS_LIMIT) {
            return false; // playing, but the capture hears nothing
        }
        return analyserStream.getAudioTracks().some(function (track) {
            return track.readyState === "live";
        });
    }

    function resetAnalyser() {
        try {
            if (analyserSource) {
                analyserSource.disconnect();
            }
        } catch (error) { /* already disconnected */ }
        analyserSource = null;
        analyserStream = null;
        analyserVideo = null;
        analyserSrc = "";
        analyserKey = null;
        silentTicks = 0;
        analyser = null;
        analyserState = "idle";
    }

    /** n bars (0-255), log spaced between ~45 Hz and ~14 kHz. */
    function computeLevels(n) {
        if (audioCtx && audioCtx.state === "suspended") {
            audioCtx.resume().catch(function () { });
        }
        analyser.getByteFrequencyData(freqData);
        let anySound = false;
        for (let k = 0; k < freqData.length; k++) {
            if (freqData[k] > 0) {
                anySound = true;
                break;
            }
        }
        const playing = getVideo();
        silentTicks = (!anySound && playing && !playing.paused) ? silentTicks + 1 : 0;
        const binHz = audioCtx.sampleRate / analyser.fftSize;
        const minHz = 45;
        const maxHz = Math.min(14000, audioCtx.sampleRate / 2);
        const ratio = maxHz / minHz;
        const raw = new Array(n);
        let framePeak = 0;
        for (let i = 0; i < n; i++) {
            const lo = Math.floor(minHz * Math.pow(ratio, i / n) / binHz);
            const hi = Math.max(lo, Math.ceil(minHz * Math.pow(ratio, (i + 1) / n) / binHz) - 1);
            let peak = 0;
            for (let b = lo; b <= hi && b < freqData.length; b++) {
                if (freqData[b] > peak) {
                    peak = freqData[b];
                }
            }
            // Highs are quieter than lows in most music: lift them a little.
            raw[i] = Math.min(1, (peak / 255) * (1 + 0.3 * (i / n)));
            if (raw[i] > framePeak) {
                framePeak = raw[i];
            }
        }

        // Auto gain: follow the loudest recent level (it rises fast and falls slowly), so a loud
        // song and a quiet one both use the whole height instead of the loud one sitting at the top.
        levelRef = Math.max(framePeak, levelRef * 0.992, 0.35);

        const out = new Array(n);
        for (let i = 0; i < n; i++) {
            const v = Math.pow(Math.min(1, raw[i] / levelRef), 1.8); // steeper: only real peaks reach the top
            out[i] = Math.round(v * 255);
        }
        return out;
    }

    // ---- the feed ----

    function startFeed(state) {
        feedVersion = state.version;
        feedStartedAt = Date.now();
        feedTrackKey = null;
        currentInfo = null;
        lastTrackCheck = 0;
        lastLyricText = null;   // the new command cleared the game side: send the line again
        lastStatus = null;      // and the status too, so a visualizer problem is shown every time
        if (!feedTimer) {
            feedTimer = setInterval(feedTick, FEED_MS);
        }
    }

    function stopFeed() {
        if (feedTimer) {
            clearInterval(feedTimer);
            feedTimer = null;
        }
        feedVersion = null;
    }

    /** Sends a payload to the game. Only one request at a time: if busy, it is skipped. */
    function push(payload) {
        if (pushInFlight) {
            return false;
        }
        pushInFlight = true;
        bridge({ type: "push", payload: payload })
            .catch(function () { /* the game may have been closed */ })
            .then(function () { pushInFlight = false; });
        return true;
    }

    function refreshTrack(video) {
        const info = trackInfo(video);
        if (!normalize(info.title)) {
            return;
        }
        const key = trackKey(info);
        if (key !== feedTrackKey) {
            feedTrackKey = key;
            lastLyricText = null;
            currentLyrics = null;
            lyricsFor = null;
        }
        currentInfo = info;
        if (settings.lyrics && info.duration > 0 && Date.now() >= lyricsRetryAt &&
            lyricsFor !== key + "|" + Math.round(info.duration)) {
            loadLyrics(info);
        }
    }

    function feedTick() {
        if (feedVersion === null || feedVersion !== lastVersion) {
            stopFeed(); // a newer command replaced the one this feed belongs to
            return;
        }
        const video = getVideo();
        const now = Date.now();
        if (!video || video.paused || now - feedStartedAt < FEED_START_DELAY_MS) {
            return;
        }
        if (now - lastTrackCheck >= TRACK_CHECK_MS || currentInfo === null) {
            lastTrackCheck = now;
            refreshTrack(video);
        }

        const payload = { v: feedVersion };
        const commits = [];

        // 1) "now playing", once per song
        if (currentInfo && announcedKey !== feedTrackKey) {
            const key = feedTrackKey;
            payload.now = { title: currentInfo.title, artist: currentInfo.artist };
            commits.push(function () { announcedKey = key; });
        }

        // 2) the lyric line, only when it changes
        const wanted = desiredLyric(video);
        if (wanted !== (lastLyricText === null ? "" : lastLyricText)) {
            payload.lyric = wanted;
            commits.push(function () { lastLyricText = wanted; });
        }

        // 3) audio levels, if the visualizer is on
        if (settings.visualizer) {
            if (analyserState === "failed" && now >= analyserRetryAt) {
                resetAnalyser();
            } else if (analyserState === "ready" && !analyserIsHealthy()) {
                resetAnalyser(); // new song or new <video>: capture it again
            }
            if (analyserState === "idle") {
                startAnalyser();
            }
            if (analyserState === "ready") {
                payload.b = computeLevels(settings.bars);
            }
        }

        // 4) a message for the game's chat (only shown there when debugChat is on)
        if (pendingStatus !== null) {
            const text = pendingStatus;
            payload.status = text;
            commits.push(function () { if (pendingStatus === text) { pendingStatus = null; } });
        }

        if (Object.keys(payload).length > 1 && push(payload)) {
            commits.forEach(function (commit) { commit(); });
        }
    }

    /** Checks that the player is paused and then tells the mod. */
    function verifyPause(state) {
        setTimeout(function () {
            const video = getVideo();
            if (!video || video.paused) {
                report(state, "paused");
            }
            // If it is still playing we do not report: the mod will restore the volume when its wait runs out.
        }, VERIFY_PAUSE_MS);
    }

    function onPause(state) {
        const hadAttempt = attemptInProgress;
        attemptInProgress = false;
        stopFeed();
        // A soft pause (game menu, death) keeps the memory of what we announced, so the
        // "now playing" message does not show again when the song resumes.
        if (!state || state.suspended !== true) {
            announcedKey = null;
        }

        if (!startedByMod && !hadAttempt) {
            console.log("[MobMusic] pause ignored: the mod did not start this music");
            verifyPause(state);
            return;
        }
        const video = getVideo();
        if (video) {
            video.loop = false;
            if (!video.paused) {
                video.pause();
            }
        }
        startedByMod = false;
        currentSong = null;
        console.log("[MobMusic] paused");
        verifyPause(state);
    }

    function apply(state) {
        commandCounter++; // invalidates the attempts of earlier commands
        if (state.command === "play") {
            onPlay(state);
        } else if (state.command === "pause") {
            onPause(state);
        }
    }

    async function poll() {
        let wait = POLL_MS;
        try {
            const state = await bridge({ type: "state" });
            readSettings(state);

            if (!gameConnected) {
                // First read after connecting: it is our reference.
                gameConnected = true;
                lastVersion = state.version;
                console.log("[MobMusic] connected to the game. Initial state:", state);
                if (state.command === "play") {
                    apply(state); // a fight was already going on
                }
            } else if (state.version !== lastVersion) {
                lastVersion = state.version;
                console.log("[MobMusic] change:", state);
                apply(state);
            }
        } catch (error) {
            if (gameConnected) {
                console.warn("[MobMusic] lost the connection with the game");
                commandCounter++;
                onPause(null); // if the game was closed, do not leave it playing (no report to the mod)
            }
            gameConnected = false;
            lastVersion = null;
            wait = RETRY_MS;
        }
        setTimeout(poll, wait);
    }

    console.log("[MobMusic] bridge loaded on", location.href);
    primeAudioOnFirstGesture();
    poll();
})();
