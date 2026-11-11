// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * Simple HTTP and HTTPS for slow but reliable fetching and sending of small pieces of information (news, a version
 * file, a leaderboard, a configuration; GET to fetch, POST to send, for example a score). Requests run in the background and never block the game loop; the game polls the returned
 * {@link HttpRequest} (no callbacks, no allocation while polling). It is not meant for real-time traffic:
 * for a game that talks to a server every frame use something else.
 * {@code engine.getHttp()} gives the default implementation, {@link UrlHttp}.
 */
public interface Http {
    /**
     * Starts a GET request in the background. Never throws and never returns null: a bad URL gives a request that
     * is already finished and failed.
     */
    HttpRequest get(String url);

    /**
     * Starts a POST request in the background: sends {@code body} (null = empty) with the given Content-Type (null =
     * application/octet-stream) and collects the server's answer like {@link #get} does. A POST is not repeated
     * automatically after an error, because the server may already have processed it (only when it was clearly never
     * sent, for example when the server cannot be found). Never throws and never returns null.
     */
    HttpRequest post(String url, byte[] body, String contentType);

    /** Stops the background work: unfinished requests fail. Call when the program ends. */
    void release();
}
