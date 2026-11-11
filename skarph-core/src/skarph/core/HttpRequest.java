// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

import java.net.HttpURLConnection;

/**
 * One HTTP request and its outcome. Created by {@link Http#get}, filled in by a background thread, read by the game
 * thread: poll {@link #isDone()} (for example once per tick), then look at {@link #isSuccess()}.
 * All getters are safe to call from any thread and do not allocate (except {@link #getText()}).
 */
public final class HttpRequest {
    /** Still queued or running (retries included). */
    public static final int PENDING = 0;
    /** Finished with a 2xx answer: {@link #getBody()} is set. */
    public static final int OK = 1;
    /** Finished without an answer or with an error status: {@link #getError()} says why. */
    public static final int FAILED = 2;
    /** Stopped by {@link #cancel()}. */
    public static final int CANCELLED = 3;

    private final String url;
    private final String method;
    private final byte[] requestBody;
    private final String requestType;
    private volatile int state = PENDING;
    private volatile int statusCode;
    private volatile byte[] body;
    private volatile String contentType;
    private volatile String error;
    private volatile int attempts;
    private volatile int received;
    private volatile int total = -1;
    private volatile HttpURLConnection connection;

    HttpRequest(String url) {
        this(url, "GET", null, null);
    }

    HttpRequest(String url, String method, byte[] requestBody, String requestType) {
        this.url = url;
        this.method = method;
        this.requestBody = requestBody;
        this.requestType = requestType;
    }

    /** A request that is already finished with a successful answer (for fakes in tests). */
    public static HttpRequest completed(String url, int statusCode, byte[] body, String contentType) {
        HttpRequest r = new HttpRequest(url);
        r.finishOk(statusCode, body != null ? body : new byte[0], contentType);
        return r;
    }

    /** A request that is already finished and failed (statusCode 0 = no answer at all). */
    public static HttpRequest failed(String url, int statusCode, String error) {
        HttpRequest r = new HttpRequest(url);
        r.finishFailed(statusCode, error);
        return r;
    }

    /** "GET" or "POST". */
    public String getMethod() {
        return method;
    }

    /** The URL that was asked for (redirects are not reflected here). */
    public String getUrl() {
        return url;
    }

    /** {@link #PENDING}, {@link #OK}, {@link #FAILED} or {@link #CANCELLED}. */
    public int getState() {
        return state;
    }

    /** True when the request is over, whatever the outcome. */
    public boolean isDone() {
        return state != PENDING;
    }

    /** True when the request finished with a 2xx answer. */
    public boolean isSuccess() {
        return state == OK;
    }

    /** The last HTTP status code received (404, 200, ...), 0 while there is none. */
    public int getStatusCode() {
        return statusCode;
    }

    /** The answer, or null unless {@link #isSuccess()}. The array is not copied: do not change it. */
    public byte[] getBody() {
        return state == OK ? body : null;
    }

    /** The answer as UTF-8 text (allocates), or null unless {@link #isSuccess()}. */
    public String getText() {
        byte[] b = getBody();
        if (b == null) {
            return null;
        }
        try {
            return new String(b, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 not supported");
        }
    }

    /** The Content-Type header of the answer, or null when there is none or the request did not succeed. */
    public String getContentType() {
        return state == OK ? contentType : null;
    }

    /** What went wrong, or null unless the state is {@link #FAILED}. */
    public String getError() {
        return state == FAILED ? error : null;
    }

    /** How many times the request was tried so far (1 = no retry was needed). */
    public int getAttempts() {
        return attempts;
    }

    /** Bytes of the answer received so far in the current attempt (for a progress bar). */
    public int getReceivedBytes() {
        return received;
    }

    /** Size of the answer from its Content-Length header, -1 while unknown. */
    public int getTotalBytes() {
        return total;
    }

    /**
     * Gives up on the request: it is over at once (state {@link #CANCELLED}, no body) and the background thread
     * abandons the connection. Does nothing when the request is already over.
     */
    public void cancel() {
        HttpURLConnection c = connection; // finish() clears it
        if (finish(CANCELLED, 0, null, null, null) && c != null) {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------- used by UrlHttp (same package)

    boolean isPost() {
        return "POST".equals(method);
    }

    byte[] requestBody() {
        return requestBody;
    }

    String requestType() {
        return requestType;
    }

    boolean isCancelled() {
        return state == CANCELLED;
    }

    void setAttempt(int n) {
        attempts = n;
        received = 0;
        total = -1;
    }

    void setProgress(int receivedBytes, int totalBytes) {
        received = receivedBytes;
        total = totalBytes;
    }

    void setConnection(HttpURLConnection c) {
        connection = c;
        if (c != null && state == CANCELLED) {
            c.disconnect(); // cancelled between two attempts
        }
    }

    boolean finishOk(int status, byte[] data, String type) {
        return finish(OK, status, data, type, null);
    }

    boolean finishFailed(int status, String message) {
        return finish(FAILED, status, null, null, message != null ? message : "request failed");
    }

    private synchronized boolean finish(int newState, int status, byte[] data, String type, String message) {
        if (state != PENDING) {
            return false;
        }
        if (status != 0) {
            statusCode = status;
        }
        body = data;
        contentType = type;
        error = message;
        connection = null;
        state = newState; // the volatile write that publishes the fields above
        return true;
    }
}
