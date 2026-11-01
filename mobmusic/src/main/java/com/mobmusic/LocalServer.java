package com.mobmusic;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Tiny HTTP server that only listens on 127.0.0.1.
 *
 *   GET /state                              -> the current command as JSON
 *   GET /report?version=N&state=playing     -> the extension's answer to command N
 *                                              (state: playing | paused | failed)
 *   POST /push                              -> what the extension sends about the song:
 *                                              "now playing" text, lyric line, audio levels
 *                                              (see MediaState). Needs the header X-MobMusic.
 */
public class LocalServer {

    // Only the YouTube Music page may read the responses from a page script
    private static final String ALLOWED_ORIGIN = "https://music.youtube.com";

    private static HttpServer server;

    public static void start(int port) {
        try {
            // 127.0.0.1 = this computer only. Nobody on your network can connect.
            server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
            server.createContext("/state", new StateHandler());
            server.createContext("/report", new ReportHandler());
            server.createContext("/push", new PushHandler());
            // Two threads: /push can arrive 20 times a second and must not delay /state.
            server.setExecutor(Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "MobMusic-HTTP");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
            MobMusic.LOGGER.info("Local server ready at http://127.0.0.1:" + port + "/state");
        } catch (IOException e) {
            // If the port is busy the mod keeps working, just without the server.
            MobMusic.LOGGER.warn("Could not open the local server on port " + port
                    + ": " + e.getMessage());
        }
    }

    /** Sends a text response with the usual headers. */
    private static void respond(HttpExchange exchange, String type, String text)
            throws IOException {
        respond(exchange, 200, type, text);
    }

    private static void respond(HttpExchange exchange, int code, String type, String text)
            throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);

        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", type + "; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        headers.set("Access-Control-Allow-Origin", ALLOWED_ORIGIN);

        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** /state: the current command. */
    private static class StateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            respond(exchange, "application/json", MusicState.asJson());
        }
    }

    /** /report?version=N&state=playing|paused|failed : the extension's answer. */
    private static class ReportHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            int version = -1;
            String state = "";

            String query = exchange.getRequestURI().getQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    int equals = pair.indexOf('=');
                    if (equals <= 0) {
                        continue;
                    }
                    String key = pair.substring(0, equals);
                    String value = pair.substring(equals + 1);
                    if (key.equals("version")) {
                        try {
                            version = Integer.parseInt(value);
                        } catch (NumberFormatException e) {
                            version = -1;
                        }
                    } else if (key.equals("state")) {
                        state = value;
                    }
                }
            }

            if (state.equals("playing") || state.equals("paused") || state.equals("failed")) {
                MusicState.registerReport(version, state);
            }
            respond(exchange, "text/plain", "ok");
        }
    }

    /** Reads at most {@code max} bytes of the request body as UTF-8. */
    private static String readBody(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > max) {
                throw new IOException("body too large");
            }
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * POST /push: the extension sends what it knows about the song (see MediaState).
     *
     * Only a POST with the custom header X-MobMusic is accepted. A web page cannot send a custom
     * header to this server without a CORS preflight, and this server never answers one, so
     * other pages in the browser cannot feed it fake data.
     */
    private static class PushHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"POST".equals(exchange.getRequestMethod())
                        || exchange.getRequestHeaders().getFirst("X-MobMusic") == null) {
                    respond(exchange, 403, "text/plain", "forbidden");
                    return;
                }
                String body = readBody(exchange.getRequestBody(), 8192);
                MediaState.applyPush(body);
                respond(exchange, "text/plain", "ok");
            } catch (Exception e) {
                // Bad JSON, too large, ...: tell the extension and keep going.
                try {
                    respond(exchange, 400, "text/plain", "bad request");
                } catch (IOException ignored) {
                    // the connection is already gone
                }
            }
        }
    }
}
