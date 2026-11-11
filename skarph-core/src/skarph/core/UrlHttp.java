// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpRetryException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.LinkedList;

/**
 * {@link Http} on java.net.HttpURLConnection: the same code on Android and on the desktop, HTTPS included (certificates
 * are checked by the platform). Built for reliability, not speed:
 * <ul>
 * <li>one background thread works through the requests one after another;</li>
 * <li>connect timeout, read timeout and a limit for the whole attempt, so a server that sends one byte a minute cannot
 *     hold a request forever;</li>
 * <li>a failed attempt (timeout, dropped connection, answer cut short, status 408, 429 or 5xx) is repeated after a
 *     growing pause; Retry-After is respected. Other 4xx statuses, bad URLs and certificate errors fail at once;</li>
 * <li>redirects are followed (also http to https, never https to http);</li>
 * <li>an answer bigger than {@link #setMaxBytes} is refused, a body that is shorter than its Content-Length is an error.</li>
 * </ul>
 * GET and POST. A POST is never repeated after an error, because the server may have processed it already; it is only
 * tried again when nothing can have been sent (the server cannot be found, the connection is refused). After a 301, 302 or
 * 303 answer a POST continues as a GET without the body, as browsers do; 307 and 308 keep method and body. The body of a
 * POST is meant to be small (see {@link #setMaxUploadBytes}). Set the options before the first request.
 * On Android the program needs {@code <uses-permission android:name="android.permission.INTERNET"/>}; HTTPS to modern
 * servers needs a device whose TLS supports their protocol version (very old Android versions do not).
 */
public final class UrlHttp implements Http {
    private static final int MAX_RETRY_AFTER_MS = 30000;

    private int connectTimeoutMs = 10000;
    private int readTimeoutMs = 20000;
    private int attemptTimeoutMs = 60000;
    private int maxAttempts = 3;
    private int retryDelayMs = 1000;
    private int maxRedirects = 5;
    private int maxBytes = 8 * 1024 * 1024;
    private int maxUploadBytes = 1024 * 1024;
    private String userAgent = "Skarph/" + Engine.VERSION;

    private final LinkedList<HttpRequest> queue = new LinkedList<HttpRequest>();
    private Thread worker;
    private boolean released;

    /** Connect and read timeout in milliseconds (a read timeout counts the silence between two pieces of data). */
    public UrlHttp setTimeouts(int connectMillis, int readMillis) {
        connectTimeoutMs = Math.max(1, connectMillis);
        readTimeoutMs = Math.max(1, readMillis);
        return this;
    }

    /** Upper limit for one attempt from the start to the last byte, in milliseconds (default 60000). */
    public UrlHttp setAttemptTimeout(int millis) {
        attemptTimeoutMs = Math.max(1, millis);
        return this;
    }

    /** How often a request is tried in total (1 = never repeat, default 3) and the pause before the second try (default 1000 ms; before the third it is twice as long, and so on). */
    public UrlHttp setRetries(int maxAttempts, int retryDelayMillis) {
        this.maxAttempts = Math.max(1, Math.min(10, maxAttempts));
        this.retryDelayMs = Math.max(0, retryDelayMillis);
        return this;
    }

    /** Largest accepted answer in bytes (default 8 MiB). */
    public UrlHttp setMaxBytes(int bytes) {
        maxBytes = Math.max(1, bytes);
        return this;
    }

    /** Largest body of a POST in bytes (default 1 MiB); a bigger one fails at once. Meant for small data such as scores. */
    public UrlHttp setMaxUploadBytes(int bytes) {
        maxUploadBytes = Math.max(0, bytes);
        return this;
    }

    /** How many redirects are followed (default 5). */
    public UrlHttp setMaxRedirects(int count) {
        maxRedirects = Math.max(0, count);
        return this;
    }

    /** The User-Agent header (default "Skarph/" and the engine version). */
    public UrlHttp setUserAgent(String agent) {
        userAgent = agent != null ? agent : "Skarph";
        return this;
    }

    public HttpRequest get(String url) {
        return start(url, "GET", null, null);
    }

    public HttpRequest post(String url, byte[] body, String contentType) {
        byte[] b = body != null ? body : new byte[0];
        if (b.length > maxUploadBytes) {
            return failedRequest(url, "POST", "the body is " + b.length + " bytes, more than the allowed " + maxUploadBytes);
        }
        return start(url, "POST", b, contentType != null ? contentType : "application/octet-stream");
    }

    private static HttpRequest failedRequest(String url, String method, String error) {
        HttpRequest r = new HttpRequest(url != null ? url : "", method, null, null);
        r.finishFailed(0, error);
        return r;
    }

    private HttpRequest start(String url, String method, byte[] body, String type) {
        if (url == null) {
            return failedRequest(url, method, "no URL");
        }
        String bad = checkUrl(url);
        if (bad != null) {
            return failedRequest(url, method, bad);
        }
        HttpRequest r = new HttpRequest(url, method, body, type);
        synchronized (queue) {
            if (released) {
                r.finishFailed(0, "the network has been released");
                return r;
            }
            queue.addLast(r);
            if (worker == null) {
                worker = new Thread(new Runnable() {
                    public void run() {
                        work();
                    }
                }, "skarph-http");
                worker.setDaemon(true);
                worker.start();
            }
            queue.notifyAll();
        }
        return r;
    }

    public void release() {
        synchronized (queue) {
            released = true;
            queue.notifyAll();
        }
    }

    // ---------------------------------------------------------------- background thread

    private void work() {
        while (true) {
            HttpRequest r;
            synchronized (queue) {
                while (queue.isEmpty() && !released) {
                    try {
                        queue.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (queue.isEmpty()) {
                    return; // released and nothing left
                }
                r = queue.removeFirst();
                if (released) {
                    r.finishFailed(0, "the network has been released");
                    continue;
                }
            }
            try {
                process(r);
            } catch (Throwable t) { // the thread must survive anything; the request must not stay pending
                r.finishFailed(0, "unexpected error: " + t);
            }
        }
    }

    /** What one attempt found out. */
    private static final class Attempt {
        boolean retry;       // worth trying again
        boolean neverSent;   // the request cannot have reached the server (a POST may be repeated then)
        long waitMs;         // the server asked for this pause (Retry-After)
        int status;
        String error;
    }

    private void process(HttpRequest r) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (r.isCancelled()) {
                return;
            }
            if (isReleased()) {
                r.finishFailed(0, "the network has been released");
                return;
            }
            r.setAttempt(attempt);
            Attempt a = new Attempt();
            byte[] body = null;
            String[] type = new String[1];
            try {
                body = fetchOnce(r, a, type);
            } catch (SecurityException e) {
                a.error = "the program may not use the network (on Android: add the INTERNET permission to AndroidManifest.xml)";
            } catch (UnknownHostException e) {
                a.error = "cannot find the server " + e.getMessage() + " (no network, a wrong name, or on Android a missing INTERNET permission)";
                a.retry = true;
                a.neverSent = true;
            } catch (ConnectException e) {
                a.error = String.valueOf(e.getMessage() != null ? e.getMessage() : e);
                a.retry = true;
                a.neverSent = true;
            } catch (HttpRetryException e) { // a POST answered with 401 or 407: java.net cannot go on in streaming mode
                a.status = e.responseCode();
                a.error = "HTTP " + e.responseCode();
            } catch (SocketTimeoutException e) {
                a.error = "timeout: " + e.getMessage();
                a.retry = true;
            } catch (IOException e) {
                a.error = String.valueOf(e.getMessage() != null ? e.getMessage() : e);
                a.retry = !isCertificateProblem(e);
                if (!a.retry) {
                    a.error = "the certificate of the server is not trusted: " + a.error;
                }
            } catch (OutOfMemoryError e) {
                a.error = "out of memory for the answer";
            } catch (RuntimeException e) {
                a.error = "bad request: " + e;
            }
            if (r.isCancelled()) {
                return;
            }
            if (body != null) {
                r.finishOk(a.status, body, type[0]);
                return;
            }
            boolean notRepeated = false;
            if (a.retry && r.isPost() && !a.neverSent) {
                a.retry = false; // the server may have processed it
                notRepeated = true;
            }
            if (!a.retry || attempt == maxAttempts) {
                r.finishFailed(a.status, a.error + (attempt > 1 ? " (after " + attempt + " attempts)" : "")
                        + (notRepeated ? " (a POST is not repeated: the server may have processed it)" : ""));
                return;
            }
            sleep(r, Math.max((long) retryDelayMs * attempt, a.waitMs));
        }
    }

    /** One attempt including the redirects. @return the body, or null with a.error / a.retry set */
    private byte[] fetchOnce(HttpRequest r, Attempt a, String[] type) throws IOException {
        long deadline = System.currentTimeMillis() + attemptTimeoutMs;
        String current = r.getUrl();
        boolean post = r.isPost();
        int redirects = 0;
        while (true) {
            URL url = new URL(current);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            r.setConnection(c);
            try {
                c.setConnectTimeout(connectTimeoutMs);
                c.setReadTimeout(readTimeoutMs);
                c.setInstanceFollowRedirects(false);
                c.setUseCaches(false);
                c.setRequestProperty("User-Agent", userAgent);
                c.setRequestProperty("Accept-Encoding", "identity"); // no transparent gzip: Content-Length stays honest
                if (post) {
                    sendBody(r, c, deadline);
                }
                int code = c.getResponseCode();
                a.status = code;
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null || loc.length() == 0) {
                        a.error = "HTTP " + code + " redirect without a Location";
                        return null;
                    }
                    String next = new URL(url, loc).toString();
                    String bad = checkUrl(next);
                    if (bad != null) {
                        a.error = "redirect to a bad address: " + bad;
                        return null;
                    }
                    if (url.getProtocol().equals("https") && next.regionMatches(true, 0, "http:", 0, 5)) {
                        a.error = "refusing a redirect from https to http";
                        return null;
                    }
                    if (++redirects > maxRedirects) {
                        a.error = "too many redirects";
                        return null;
                    }
                    if (post && (code == 301 || code == 302 || code == 303)) {
                        post = false; // browsers do the same: the answer to a POST is fetched with a GET
                    }
                    current = next;
                    continue;
                }
                if (code < 200 || code > 299) {
                    a.error = "HTTP " + code;
                    a.retry = code == 408 || code == 429 || code >= 500;
                    if (a.retry) {
                        a.waitMs = retryAfter(c.getHeaderField("Retry-After"));
                    }
                    return null;
                }
                int length = c.getContentLength();
                if (length > maxBytes) {
                    a.error = "the answer is " + length + " bytes, more than the allowed " + maxBytes;
                    return null;
                }
                r.setProgress(0, length);
                type[0] = c.getContentType();
                return readBody(r, c, length, deadline, a);
            } finally {
                r.setConnection(null);
                c.disconnect();
            }
        }
    }

    private void sendBody(HttpRequest r, HttpURLConnection c, long deadline) throws IOException {
        byte[] body = r.requestBody();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(body.length);
        c.setRequestProperty("Content-Type", r.requestType());
        OutputStream out = c.getOutputStream();
        try {
            int off = 0;
            while (off < body.length) {
                if (r.isCancelled()) {
                    throw new IOException("cancelled");
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new SocketTimeoutException("the attempt took longer than " + attemptTimeoutMs + " ms");
                }
                int n = Math.min(8192, body.length - off);
                out.write(body, off, n);
                off += n;
            }
            out.flush();
        } finally {
            try {
                out.close();
            } catch (IOException ignored) {
                // the answer decides
            }
        }
    }

    private byte[] readBody(HttpRequest r, HttpURLConnection c, int length, long deadline, Attempt a) throws IOException {
        InputStream in = c.getInputStream();
        try {
            byte[] buf = new byte[length >= 0 ? length : Math.min(8192, maxBytes)];
            int n = 0;
            while (true) {
                if (r.isCancelled()) {
                    throw new IOException("cancelled");
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new SocketTimeoutException("the attempt took longer than " + attemptTimeoutMs + " ms");
                }
                if (n == buf.length) {
                    if (length >= 0) {
                        int probe = in.read();
                        if (probe < 0) {
                            break;
                        }
                        a.error = "the answer is longer than its Content-Length";
                        return null;
                    }
                    if (buf.length >= maxBytes) {
                        if (in.read() < 0) {
                            break; // exactly maxBytes: fine
                        }
                        a.error = "the answer is bigger than the allowed " + maxBytes + " bytes";
                        return null;
                    }
                    byte[] bigger = new byte[(int) Math.min((long) maxBytes, (long) buf.length * 2)];
                    System.arraycopy(buf, 0, bigger, 0, n);
                    buf = bigger;
                }
                int got = in.read(buf, n, buf.length - n);
                if (got < 0) {
                    break;
                }
                n += got;
                r.setProgress(n, length);
            }
            if (length >= 0 && n != length) {
                throw new IOException("the connection closed after " + n + " of " + length + " bytes");
            }
            if (n == buf.length) {
                return buf;
            }
            byte[] exact = new byte[n];
            System.arraycopy(buf, 0, exact, 0, n);
            return exact;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** @return null when the URL is a usable http or https address, else the reason */
    private static String checkUrl(String url) {
        URL u;
        try {
            u = new URL(url);
        } catch (MalformedURLException e) {
            return "bad URL: " + e.getMessage();
        }
        String p = u.getProtocol();
        if (!p.equals("http") && !p.equals("https")) {
            return "only http and https addresses are supported, not " + p;
        }
        if (u.getHost() == null || u.getHost().length() == 0) {
            return "the URL has no host";
        }
        return null;
    }

    private static boolean isCertificateProblem(Throwable t) {
        for (int i = 0; t != null && i < 8; i++, t = t.getCause()) {
            if (t instanceof java.security.cert.CertificateException) {
                return true;
            }
        }
        return false;
    }

    /** Retry-After in seconds -> milliseconds (a date is ignored), at most 30 s. */
    private static long retryAfter(String header) {
        if (header == null) {
            return 0;
        }
        header = header.trim();
        if (header.length() == 0 || header.length() > 6) {
            return 0;
        }
        long s = 0;
        for (int i = 0; i < header.length(); i++) {
            char ch = header.charAt(i);
            if (ch < '0' || ch > '9') {
                return 0;
            }
            s = s * 10 + (ch - '0');
        }
        return Math.min(s * 1000L, MAX_RETRY_AFTER_MS);
    }

    private boolean isReleased() {
        synchronized (queue) {
            return released;
        }
    }

    /** Waits in short slices so that cancel() and release() are noticed. */
    private void sleep(HttpRequest r, long millis) {
        long end = System.currentTimeMillis() + millis;
        while (!r.isCancelled()) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) {
                return;
            }
            if (isReleased()) {
                return;
            }
            try {
                Thread.sleep(Math.min(left, 50));
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
