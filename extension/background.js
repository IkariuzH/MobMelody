// MobMusic Bridge - background service worker.
//
// Why this file exists: recent versions of Chrome / Edge / Brave block web pages
// (and content scripts, which act as the page) from talking to 127.0.0.1
// ("Local Network Access"). The extension's own background worker is not
// affected, so it is the one that talks to the game. content.js only asks it
// to do so through chrome.runtime.sendMessage.
//
// It also fetches synced lyrics from LRCLIB (https://lrclib.net), a free lyrics database that
// needs no key. This only happens if "showLyrics" is on in the mod's config.

const PORT = 8765; // must match the `port` option in config/mobmusic.cfg
const BASE = "http://127.0.0.1:" + PORT;

const LRCLIB = "https://lrclib.net/api";
const DURATION_TOLERANCE_S = 5;       // how far the length may differ and still be "the same song"
const STRICT_DURATION_TOLERANCE_S = 2; // for the loosest search (title only)

/** Builds "a=1&b=2" with everything encoded. */
function query(params) {
    return Object.keys(params)
        .map(function (key) {
            return encodeURIComponent(key) + "=" + encodeURIComponent(params[key]);
        })
        .join("&");
}

/** GET + JSON. A 404 is "not found" (null), any other error throws. */
async function getJson(url) {
    const response = await fetch(url, { cache: "no-store" });
    if (response.status === 404) {
        return null;
    }
    if (!response.ok) {
        throw new Error("HTTP " + response.status);
    }
    return response.json();
}

/**
 * The title as written by YouTube Music, and the same without the extras that lyric databases
 * leave out: "Song (feat. X)", "Song (con X)", "Song [Remastered]", "Song - Remastered 2011"...
 */
function titleVariants(title) {
    const list = [title];
    let clean = title
        .replace(/\s*[\(\[][^\)\]]*[\)\]]/g, " ")
        .replace(/\s+-\s+(?:.*(?:remaster|live|version|mono|stereo|edit|radio|single|bonus|deluxe).*)$/i, " ")
        .replace(/\s+(?:feat\.?|ft\.?|featuring|con|with)\s+.*$/i, " ")
        .replace(/\s+/g, " ")
        .trim();
    if (clean && clean !== title) {
        list.push(clean);
    }
    return list;
}

/** The artist as written, and only the first one ("A, B & C" -> "A"). */
function artistVariants(artist) {
    const list = [artist];
    const first = artist.split(/\s*(?:,|&|;|\/|\sy\s|\sand\s|\sx\s|\sfeat\.?\s|\sft\.?\s|\scon\s|\swith\s)\s*/i)[0].trim();
    if (first && first !== artist) {
        list.push(first);
    }
    return list;
}

/** From a list of LRCLIB results, the one with synced lyrics and the closest duration. */
function bestSynced(list, duration, tolerance) {
    let best = null;
    let bestGap = Infinity;
    if (!Array.isArray(list)) {
        return null;
    }
    list.forEach(function (item) {
        if (!item || !item.syncedLyrics) {
            return;
        }
        const gap = Math.abs((Number(item.duration) || 0) - duration);
        if (gap < bestGap) {
            best = item;
            bestGap = gap;
        }
    });
    return best && bestGap <= tolerance ? best : null;
}

function found(item) {
    return { synced: item.syncedLyrics, track: item.trackName || "", artist: item.artistName || "" };
}

/**
 * Looks for the synced lyrics of a song. Returns { synced, track, artist }, { instrumental: true },
 * or null if there is nothing usable. It tries the title as it is, then without "(feat. ...)"
 * style extras, and the full artist, then only the first one. The duration is always checked
 * here: LRCLIB can answer with another edit of the same song and its timestamps would be wrong.
 */
async function findLyrics(info) {
    const titles = titleVariants(info.title);
    const artists = artistVariants(info.artist);

    for (const t of titles) {
        for (const a of artists) {
            if (info.album && info.duration) {
                const hit = await getJson(LRCLIB + "/get?" + query({
                    track_name: t,
                    artist_name: a,
                    album_name: info.album,
                    duration: Math.round(info.duration)
                }));
                if (hit) {
                    if (hit.instrumental) {
                        return { instrumental: true };
                    }
                    if (hit.syncedLyrics &&
                        Math.abs((Number(hit.duration) || 0) - info.duration) <= DURATION_TOLERANCE_S) {
                        return found(hit);
                    }
                }
            }
            const best = bestSynced(await getJson(LRCLIB + "/search?" + query({
                track_name: t,
                artist_name: a
            })), info.duration, DURATION_TOLERANCE_S);
            if (best) {
                return found(best);
            }
        }
    }

    // Looser: only the title (the duration must then be almost exact).
    for (const t of titles) {
        const best = bestSynced(await getJson(LRCLIB + "/search?" + query({ track_name: t })),
            info.duration, STRICT_DURATION_TOLERANCE_S);
        if (best) {
            return found(best);
        }
    }

    // Last try: free text search.
    const last = bestSynced(await getJson(LRCLIB + "/search?" + query({
        q: titles[titles.length - 1] + " " + artists[artists.length - 1]
    })), info.duration, STRICT_DURATION_TOLERANCE_S);
    return last ? found(last) : null;
}

chrome.runtime.onMessage.addListener(function (message, sender, sendResponse) {
    if (!message || typeof message.type !== "string") {
        return false;
    }

    // Song info, lyric line and audio levels, from the page to the game.
    if (message.type === "push") {
        const payload = message.payload;
        if (!payload || typeof payload !== "object" || !Number.isInteger(payload.v)) {
            sendResponse({ ok: false, error: "bad push" });
            return false;
        }
        fetch(BASE + "/push", {
            method: "POST",
            // The custom header is what the mod checks. A normal web page cannot send it here
            // without a CORS preflight, which the mod never answers.
            headers: { "Content-Type": "text/plain;charset=UTF-8", "X-MobMusic": "1" },
            body: JSON.stringify(payload),
            cache: "no-store"
        })
            .then(function (response) {
                sendResponse({ ok: response.ok, data: null });
            })
            .catch(function (error) {
                sendResponse({ ok: false, error: String(error) });
            });
        return true;
    }

    // Lyrics from LRCLIB. The page asks us because we are the ones with the permission.
    if (message.type === "lyrics") {
        const info = {
            title: String(message.title || "").slice(0, 200),
            artist: String(message.artist || "").slice(0, 200),
            album: String(message.album || "").slice(0, 200),
            duration: Number(message.duration) || 0
        };
        if (!info.title || !(info.duration > 0)) {
            sendResponse({ ok: false, error: "bad lyrics request" });
            return false;
        }
        findLyrics(info)
            .then(function (data) {
                sendResponse({ ok: true, data: data });
            })
            .catch(function (error) {
                sendResponse({ ok: false, error: String(error) });
            });
        return true;
    }

    let url = null;
    if (message.type === "state") {
        url = BASE + "/state";
    } else if (message.type === "report") {
        const version = Number(message.version);
        const state = String(message.state);
        if (!Number.isInteger(version) || !/^(playing|paused|failed)$/.test(state)) {
            sendResponse({ ok: false, error: "bad report" });
            return false;
        }
        url = BASE + "/report?version=" + version + "&state=" + state;
    } else {
        return false;
    }

    fetch(url, { cache: "no-store" })
        .then(function (response) {
            return message.type === "state" ? response.json() : response.text();
        })
        .then(function (data) {
            sendResponse({ ok: true, data: data });
        })
        .catch(function (error) {
            sendResponse({ ok: false, error: String(error) });
        });

    return true; // the answer is sent asynchronously
});
