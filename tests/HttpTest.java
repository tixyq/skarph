// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import skarph.core.Engine;
import skarph.core.Http;
import skarph.core.HttpRequest;
import skarph.core.UrlHttp;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Headless tests of Http / HttpRequest / UrlHttp against real servers on the loopback interface (the JDK's own
 * HttpServer and HttpsServer; the HTTPS certificates are made with keytool): answers, errors, retries, redirects,
 * truncated and oversized bodies, stalls, cancel, release, certificates, and the Engine wiring.
 */
public class HttpTest {
    static int failed;
    static final Map<String, AtomicInteger> hits = new ConcurrentHashMap<String, AtomicInteger>();
    static final Map<String, String> seen = new ConcurrentHashMap<String, String>();

    static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failed++;
    }

    static int hit(String path) {
        AtomicInteger c = hits.get(path);
        if (c == null) {
            hits.putIfAbsent(path, new AtomicInteger());
            c = hits.get(path);
        }
        return c.incrementAndGet();
    }

    static int count(String path) {
        AtomicInteger c = hits.get(path);
        return c == null ? 0 : c.get();
    }

    static void reply(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        if (type != null) ex.getResponseHeaders().add("Content-Type", type);
        if (body == null) {
            ex.sendResponseHeaders(code, -1);
        } else {
            ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
            if (body.length > 0) ex.getResponseBody().write(body);
        }
        ex.close();
    }

    static void drain(HttpExchange ex) throws IOException {
        InputStream is = ex.getRequestBody();
        byte[] buf = new byte[8192];
        while (is.read(buf) > 0) { /* read it all */ }
    }

    static byte[] bytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * 31 + 7);
        return b;
    }

    static void redirect(HttpExchange ex, String to) throws IOException {
        ex.getResponseHeaders().add("Location", to);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    interface H { void handle(HttpExchange ex) throws Exception; }

    static void on(HttpServer s, final String path, final H h) {
        s.createContext(path, new com.sun.net.httpserver.HttpHandler() {
            public void handle(HttpExchange ex) throws IOException {
                hit(path);
                try {
                    h.handle(ex);
                } catch (IOException e) {
                    ex.close();
                } catch (Exception e) {
                    ex.close();
                }
            }
        });
    }

    static HttpRequest await(HttpRequest r, long millis) throws InterruptedException {
        long end = System.currentTimeMillis() + millis;
        while (!r.isDone() && System.currentTimeMillis() < end) Thread.sleep(5);
        return r;
    }

    static UrlHttp fast() {
        return new UrlHttp().setRetries(3, 10).setTimeouts(2000, 2000);
    }

    public static void main(String[] args) throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.setExecutor(Executors.newCachedThreadPool());
        final String base = "http://127.0.0.1:" + srv.getAddress().getPort();

        on(srv, "/ok", new H() { public void handle(HttpExchange ex) throws Exception {
            seen.put("ua", String.valueOf(ex.getRequestHeaders().getFirst("User-Agent")));
            seen.put("enc", String.valueOf(ex.getRequestHeaders().getFirst("Accept-Encoding")));
            seen.put("method", ex.getRequestMethod());
            reply(ex, 200, "text/plain; charset=utf-8", "hello \u043c\u0438\u0440".getBytes("UTF-8"));
        }});
        on(srv, "/bin", new H() { public void handle(HttpExchange ex) throws Exception { reply(ex, 200, "application/octet-stream", bytes(100000)); }});
        on(srv, "/missing", new H() { public void handle(HttpExchange ex) throws Exception { reply(ex, 404, "text/plain", "nope".getBytes()); }});
        on(srv, "/teapot", new H() { public void handle(HttpExchange ex) throws Exception { reply(ex, 418, null, null); }});
        on(srv, "/flaky", new H() { public void handle(HttpExchange ex) throws Exception {
            if (count("/flaky") <= 2) reply(ex, 503, null, null); else reply(ex, 200, "text/plain", "finally".getBytes());
        }});
        on(srv, "/flaky429", new H() { public void handle(HttpExchange ex) throws Exception {
            if (count("/flaky429") <= 1) reply(ex, 429, null, null); else reply(ex, 200, "text/plain", "calm".getBytes());
        }});
        on(srv, "/always503", new H() { public void handle(HttpExchange ex) throws Exception { reply(ex, 503, null, null); }});
        on(srv, "/always500", new H() { public void handle(HttpExchange ex) throws Exception { reply(ex, 500, null, null); }});
        on(srv, "/retryafter", new H() { public void handle(HttpExchange ex) throws Exception {
            if (count("/retryafter") <= 1) { ex.getResponseHeaders().add("Retry-After", "1"); reply(ex, 503, null, null); }
            else reply(ex, 200, "text/plain", "later".getBytes());
        }});
        on(srv, "/r1", new H() { public void handle(HttpExchange ex) throws Exception { redirect(ex, "/r2"); }});
        on(srv, "/r2", new H() { public void handle(HttpExchange ex) throws Exception { redirect(ex, base + "/ok"); }});
        on(srv, "/loop", new H() { public void handle(HttpExchange ex) throws Exception { redirect(ex, "/loop"); }});
        on(srv, "/tofile", new H() { public void handle(HttpExchange ex) throws Exception { redirect(ex, "file:///etc/passwd"); }});
        on(srv, "/truncate", new H() { public void handle(HttpExchange ex) throws Exception {
            if (count("/truncate") == 1) {
                ex.sendResponseHeaders(200, 1000);
                ex.getResponseBody().write(bytes(100));
                ex.getResponseBody().flush();
                try { ex.close(); } catch (RuntimeException e) { /* the server complains, the client sees the cut */ }
            } else {
                reply(ex, 200, "application/octet-stream", bytes(1000));
            }
        }});
        on(srv, "/big", new H() { public void handle(HttpExchange ex) throws Exception { reply(ex, 200, null, bytes(5000)); }});
        on(srv, "/chunkedbig", new H() { public void handle(HttpExchange ex) throws Exception {
            ex.sendResponseHeaders(200, 0);
            ex.getResponseBody().write(bytes(3000));
            ex.close();
        }});
        on(srv, "/exact", new H() { public void handle(HttpExchange ex) throws Exception {
            ex.sendResponseHeaders(200, 0);
            ex.getResponseBody().write(bytes(1000));
            ex.close();
        }});
        on(srv, "/stall", new H() { public void handle(HttpExchange ex) throws Exception {
            ex.sendResponseHeaders(200, 0);
            ex.getResponseBody().flush();
            Thread.sleep(2500);
            ex.close();
        }});
        on(srv, "/drip", new H() { public void handle(HttpExchange ex) throws Exception {
            ex.sendResponseHeaders(200, 0);
            for (int i = 0; i < 100; i++) {
                ex.getResponseBody().write('x');
                ex.getResponseBody().flush();
                Thread.sleep(50);
            }
            ex.close();
        }});
        on(srv, "/slow", new H() { public void handle(HttpExchange ex) throws Exception {
            Thread.sleep(1500);
            reply(ex, 200, "text/plain", "late".getBytes());
        }});
        on(srv, "/echo", new H() { public void handle(HttpExchange ex) throws Exception {
            ByteArrayOutputStream in = new ByteArrayOutputStream();
            InputStream is = ex.getRequestBody();
            byte[] buf = new byte[8192];
            for (int n; (n = is.read(buf)) > 0; ) in.write(buf, 0, n);
            seen.put("echo-method", ex.getRequestMethod());
            seen.put("echo-type", String.valueOf(ex.getRequestHeaders().getFirst("Content-Type")));
            seen.put("echo-clen", String.valueOf(ex.getRequestHeaders().getFirst("Content-Length")));
            seen.put("echo-len", String.valueOf(in.size()));
            reply(ex, 200, "application/octet-stream", in.toByteArray());
        }});
        on(srv, "/post302", new H() { public void handle(HttpExchange ex) throws Exception { drain(ex); redirect(ex, "/echo"); }});
        on(srv, "/post303", new H() { public void handle(HttpExchange ex) throws Exception { drain(ex); ex.getResponseHeaders().add("Location", "/echo"); ex.sendResponseHeaders(303, -1); ex.close(); }});
        on(srv, "/post307", new H() { public void handle(HttpExchange ex) throws Exception { drain(ex); ex.getResponseHeaders().add("Location", "/echo"); ex.sendResponseHeaders(307, -1); ex.close(); }});
        on(srv, "/post308", new H() { public void handle(HttpExchange ex) throws Exception { drain(ex); ex.getResponseHeaders().add("Location", "/echo"); ex.sendResponseHeaders(308, -1); ex.close(); }});
        on(srv, "/auth401", new H() { public void handle(HttpExchange ex) throws Exception {
            drain(ex);
            ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"x\"");
            reply(ex, 401, null, null);
        }});
        on(srv, "/empty", new H() { public void handle(HttpExchange ex) throws Exception { reply(ex, 204, null, null); }});
        srv.start();

        // ---------------------------------------------------------------- answers
        UrlHttp http = fast();
        HttpRequest r = http.get(base + "/ok");
        check(!r.isDone() || r.isSuccess(), "a new request is pending or already done, never failed");
        await(r, 5000);
        check(r.isDone() && r.isSuccess() && r.getState() == HttpRequest.OK, "GET: success");
        check(r.getStatusCode() == 200 && "hello \u043c\u0438\u0440".equals(r.getText()), "GET: status 200, text decoded as UTF-8");
        check(r.getContentType() != null && r.getContentType().startsWith("text/plain"), "GET: content type");
        check(r.getError() == null && r.getAttempts() == 1, "GET: no error, one attempt");
        check("GET".equals(seen.get("method")) && "Skarph/".concat(Engine.VERSION).equals(seen.get("ua")), "the request is a GET with the Skarph user agent");
        check("identity".equals(seen.get("enc")), "Accept-Encoding: identity (no transparent gzip, honest Content-Length)");
        check(base.equals(r.getUrl().substring(0, base.length())), "getUrl returns the asked URL");

        r = await(http.get(base + "/bin"), 5000);
        check(r.isSuccess() && java.util.Arrays.equals(r.getBody(), bytes(100000)), "a 100 KB binary body arrives intact");
        check(r.getReceivedBytes() == 100000 && r.getTotalBytes() == 100000, "progress: received and total bytes");

        r = await(http.get(base + "/empty"), 5000);
        check(r.isSuccess() && r.getStatusCode() == 204 && r.getBody().length == 0, "204 without body is a success with an empty body");

        // ---------------------------------------------------------------- errors and retries
        r = await(http.get(base + "/missing"), 5000);
        check(r.isDone() && !r.isSuccess() && r.getState() == HttpRequest.FAILED, "404 fails");
        check(r.getStatusCode() == 404 && r.getAttempts() == 1 && count("/missing") == 1, "404 is not repeated");
        check(r.getError() != null && r.getError().contains("404") && r.getBody() == null && r.getText() == null, "404: error text, no body");
        r = await(http.get(base + "/teapot"), 5000);
        check(!r.isSuccess() && count("/teapot") == 1, "other 4xx statuses are not repeated either");

        r = await(http.get(base + "/flaky"), 5000);
        check(r.isSuccess() && "finally".equals(r.getText()) && r.getAttempts() == 3 && count("/flaky") == 3, "503, 503, 200: the third attempt succeeds");
        r = await(http.get(base + "/flaky429"), 5000);
        check(r.isSuccess() && r.getAttempts() == 2, "429 is repeated");
        r = await(http.get(base + "/always500"), 5000);
        check(!r.isSuccess() && count("/always500") == 3 && r.getStatusCode() == 500, "a server that always fails 500 is tried 3 times");
        check(r.getError().contains("after 3 attempts"), "the error says how often it was tried: " + r.getError());
        long t0 = System.currentTimeMillis();
        r = await(http.get(base + "/retryafter"), 8000);
        long dt = System.currentTimeMillis() - t0;
        check(r.isSuccess() && dt >= 900, "Retry-After: 1 is respected (" + dt + " ms)");
        r = await(new UrlHttp().setRetries(1, 10).get(base + "/always500"), 5000);
        check(!r.isSuccess() && r.getAttempts() == 1, "setRetries(1, ...) never repeats");

        // ---------------------------------------------------------------- redirects
        r = await(http.get(base + "/r1"), 5000);
        check(r.isSuccess() && "hello \u043c\u0438\u0440".equals(r.getText()), "two redirects (relative and absolute) are followed");
        r = await(http.get(base + "/loop"), 5000);
        check(!r.isSuccess() && r.getError().contains("too many redirects") && count("/loop") == 6, "a redirect loop stops after 5 redirects");
        r = await(http.get(base + "/tofile"), 5000);
        check(!r.isSuccess() && r.getError().contains("only http and https"), "a redirect to file: is refused");

        // ---------------------------------------------------------------- damaged and oversized answers
        r = await(http.get(base + "/truncate"), 5000);
        check(r.isSuccess() && r.getBody().length == 1000 && r.getAttempts() == 2, "an answer cut short is detected and fetched again");
        r = await(new UrlHttp().setMaxBytes(1000).setRetries(3, 10).get(base + "/big"), 5000);
        check(!r.isSuccess() && count("/big") == 1 && r.getError().contains("1000"), "Content-Length above the limit is refused, not repeated");
        r = await(new UrlHttp().setMaxBytes(1000).setRetries(3, 10).get(base + "/chunkedbig"), 5000);
        check(!r.isSuccess() && count("/chunkedbig") == 1 && r.getError().contains("1000"), "a chunked answer above the limit is refused");
        r = await(new UrlHttp().setMaxBytes(1000).get(base + "/exact"), 5000);
        check(r.isSuccess() && r.getBody().length == 1000, "an answer of exactly the limit is accepted");

        // ---------------------------------------------------------------- stalls and time limits
        t0 = System.currentTimeMillis();
        r = await(new UrlHttp().setTimeouts(1000, 300).setRetries(2, 10).get(base + "/stall"), 8000);
        dt = System.currentTimeMillis() - t0;
        check(!r.isSuccess() && r.getError().contains("timeout") && count("/stall") == 2 && dt < 2400, "a stalled server: read timeout, one repeat, then failure (" + dt + " ms)");
        t0 = System.currentTimeMillis();
        r = await(new UrlHttp().setAttemptTimeout(500).setRetries(1, 10).get(base + "/drip"), 8000);
        dt = System.currentTimeMillis() - t0;
        check(!r.isSuccess() && r.getError().contains("longer than 500") && dt < 2000, "a server that drips one byte at a time hits the attempt limit (" + dt + " ms)");

        // ---------------------------------------------------------------- bad addresses, no server
        int before = hits.size();
        HttpRequest[] bad = {http.get(null), http.get(""), http.get("not a url"), http.get("ftp://example.com/x"), http.get("file:///etc/passwd"), http.get("http://")};
        boolean allBad = true;
        for (HttpRequest b : bad) allBad &= b.isDone() && b.getState() == HttpRequest.FAILED && b.getError() != null && b.getAttempts() == 0;
        check(allBad, "bad URLs (null, empty, text, ftp:, file:, no host) give a finished failed request at once");
        int closedPort;
        try (ServerSocket ss = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) { closedPort = ss.getLocalPort(); }
        r = await(http.get("http://127.0.0.1:" + closedPort + "/"), 8000);
        check(!r.isSuccess() && r.getAttempts() == 3 && r.getError() != null, "connection refused: 3 attempts, then a failure with a reason (" + r.getError() + ")");

        // ---------------------------------------------------------------- POST
        byte[] payload = bytes(50000);
        r = await(http.post(base + "/echo", payload, "application/x-test"), 5000);
        check(r.isSuccess() && java.util.Arrays.equals(r.getBody(), payload), "POST: 50 KB body arrives at the server and comes back intact");
        check("POST".equals(seen.get("echo-method")) && "application/x-test".equals(seen.get("echo-type")) && "50000".equals(seen.get("echo-clen")),
                "POST: method, Content-Type and Content-Length reach the server");
        check("POST".equals(r.getMethod()) && "GET".equals(http.get(base + "/ok").getMethod()), "getMethod(): POST and GET");
        r = await(http.post(base + "/echo", "name=Ann&score=42".getBytes("UTF-8"), null), 5000);
        check(r.isSuccess() && "application/octet-stream".equals(seen.get("echo-type")) && "name=Ann&score=42".equals(r.getText()), "POST: the default Content-Type is application/octet-stream");
        r = await(http.post(base + "/echo", null, "text/plain"), 5000);
        check(r.isSuccess() && r.getBody().length == 0 && "0".equals(seen.get("echo-clen")) && "POST".equals(seen.get("echo-method")), "POST: a null body is sent as an empty body");
        byte[] mega = bytes(1000000);
        r = await(http.post(base + "/echo", mega, "application/octet-stream"), 10000);
        check(r.isSuccess() && java.util.Arrays.equals(r.getBody(), mega), "POST: 1 MB (within the default limit) goes through");

        int fiveBefore = count("/always500");
        r = await(http.post(base + "/always500", payload, "text/plain"), 5000);
        check(!r.isSuccess() && r.getStatusCode() == 500 && r.getAttempts() == 1 && count("/always500") == fiveBefore + 1, "POST: status 500 is not repeated (the server is hit once, a GET would hit it 3 times)");
        check(r.getError().contains("POST is not repeated"), "POST: the error explains why it was not repeated: " + r.getError());
        r = await(http.post(base + "/always503", payload, "text/plain"), 5000);
        check(!r.isSuccess() && r.getStatusCode() == 503 && count("/always503") == 1, "POST: 503 is not repeated either");
        r = await(http.post("http://127.0.0.1:" + closedPort + "/", payload, "text/plain"), 8000);
        check(!r.isSuccess() && r.getAttempts() == 3, "POST: connection refused (nothing was sent) is repeated like a GET");
        r = await(http.post(base + "/missing", payload, "text/plain"), 5000);
        check(!r.isSuccess() && r.getStatusCode() == 404 && r.getAttempts() == 1, "POST: 404 fails with its status");
        r = await(http.post(base + "/auth401", payload, "text/plain"), 5000);
        check(!r.isSuccess() && r.getStatusCode() == 401 && r.getError().contains("401"), "POST: a 401 answer ends with status 401 (no hang, no exception)");

        r = await(http.post(base + "/post302", payload, "application/x-test"), 5000);
        check(r.isSuccess() && "GET".equals(seen.get("echo-method")) && r.getBody().length == 0, "POST: after a 302 the answer is fetched with a GET and no body");
        r = await(http.post(base + "/post303", payload, "application/x-test"), 5000);
        check(r.isSuccess() && "GET".equals(seen.get("echo-method")), "POST: 303 becomes a GET");
        r = await(http.post(base + "/post307", payload, "application/x-test"), 5000);
        check(r.isSuccess() && "POST".equals(seen.get("echo-method")) && java.util.Arrays.equals(r.getBody(), payload) && "application/x-test".equals(seen.get("echo-type")), "POST: 307 keeps method and body");
        r = await(http.post(base + "/post308", payload, "application/x-test"), 5000);
        check(r.isSuccess() && "POST".equals(seen.get("echo-method")) && java.util.Arrays.equals(r.getBody(), payload), "POST: 308 keeps method and body");

        UrlHttp small = new UrlHttp().setMaxUploadBytes(1000);
        r = small.post(base + "/echo", bytes(2000), "text/plain");
        check(r.isDone() && !r.isSuccess() && r.getError().contains("1000") && r.getAttempts() == 0 && "POST".equals(r.getMethod()), "POST: a body above the upload limit fails at once");
        r = await(small.post(base + "/echo", bytes(1000), "text/plain"), 5000);
        check(r.isSuccess(), "POST: a body of exactly the limit is accepted");
        small.release();
        HttpRequest[] badPost = {http.post(null, payload, "x"), http.post("ftp://x/y", payload, "x"), http.post("not a url", payload, "x")};
        boolean allBadPost = true;
        for (HttpRequest b : badPost) allBadPost &= b.isDone() && b.getState() == HttpRequest.FAILED && "POST".equals(b.getMethod());
        check(allBadPost, "POST: bad URLs give a finished failed request at once");

        int slowBefore2 = count("/slow");
        HttpRequest slowPost = http.post(base + "/slow", payload, "text/plain");
        while (count("/slow") == slowBefore2) Thread.sleep(5);
        slowPost.cancel();
        check(slowPost.isDone() && slowPost.getState() == HttpRequest.CANCELLED && slowPost.getBody() == null, "POST: cancel() of a running request is immediate");
        r = await(http.get(base + "/ok"), 5000);
        check(r.isSuccess(), "POST: a GET after the cancelled POST works");
        Thread.sleep(1500);

        // ---------------------------------------------------------------- cancel and queue
        UrlHttp one = fast();
        HttpRequest first = one.get(base + "/slow");
        HttpRequest second = one.get(base + "/ok");
        int okBefore = count("/ok");
        second.cancel();
        check(second.isDone() && second.getState() == HttpRequest.CANCELLED && second.getBody() == null && second.getError() == null, "cancel(): over at once, state CANCELLED, no body, no error");
        await(first, 5000);
        Thread.sleep(100);
        check(first.isSuccess() && "late".equals(first.getText()), "the request before it in the queue is not disturbed");
        check(count("/ok") == okBefore && second.getState() == HttpRequest.CANCELLED, "a request cancelled while queued never reaches the server");
        int slowBefore = count("/slow");
        HttpRequest running = one.get(base + "/slow");
        while (count("/slow") == slowBefore) Thread.sleep(5);
        t0 = System.currentTimeMillis();
        running.cancel();
        check(running.isDone() && running.getState() == HttpRequest.CANCELLED, "cancel() of a running request is immediate");
        HttpRequest after = await(one.get(base + "/ok"), 5000);
        dt = System.currentTimeMillis() - t0;
        check(after.isSuccess() && dt < 1400, "the worker abandons a cancelled request and goes on (" + dt + " ms)");
        Thread.sleep(1600);
        check(running.getState() == HttpRequest.CANCELLED && running.getBody() == null, "a cancelled request stays cancelled when the server answers later");
        HttpRequest done = await(one.get(base + "/ok"), 5000);
        done.cancel();
        check(done.isSuccess() && done.getBody() != null, "cancel() after the end changes nothing");

        // ---------------------------------------------------------------- release
        UrlHttp rel = fast();
        HttpRequest a1 = rel.get(base + "/slow");
        HttpRequest a2 = rel.get(base + "/ok");
        rel.release();
        await(a2, 5000);
        check(a2.isDone() && !a2.isSuccess(), "release(): a queued request fails");
        HttpRequest a3 = rel.get(base + "/ok");
        check(a3.isDone() && !a3.isSuccess() && a3.getError().contains("released"), "get() after release() fails at once");
        await(a1, 5000);
        check(a1.isDone(), "the request that was running when release() was called ends too");

        // ---------------------------------------------------------------- HTTPS
        httpsTests(srv, base);

        // ---------------------------------------------------------------- Engine and fakes
        Engine e = new Engine();
        Http def = e.getHttp();
        check(def instanceof UrlHttp && e.getHttp() == def, "Engine.getHttp(): a default UrlHttp, always the same one");
        final boolean[] released = {false};
        Http fake = new Http() {
            public HttpRequest get(String url) { return HttpRequest.completed(url, 200, "fake".getBytes(), "text/plain"); }
            public HttpRequest post(String url, byte[] body, String type) { return HttpRequest.completed(url, 201, body, type); }
            public void release() { released[0] = true; }
        };
        e.setHttp(fake);
        HttpRequest fr = e.getHttp().get("http://x/y");
        check(e.getHttp() == fake && fr.isSuccess() && "fake".equals(fr.getText()) && "http://x/y".equals(fr.getUrl()), "Engine.setHttp(): a fake answers, HttpRequest.completed() works");
        HttpRequest fp = e.getHttp().post("http://x/p", "data".getBytes(), "text/plain");
        check(fp.isSuccess() && fp.getStatusCode() == 201 && "data".equals(fp.getText()), "Engine: a fake can answer post() too");
        HttpRequest ff = HttpRequest.failed("http://x/z", 503, "down");
        check(ff.isDone() && !ff.isSuccess() && ff.getStatusCode() == 503 && "down".equals(ff.getError()) && ff.getBody() == null, "HttpRequest.failed() works");
        e.releaseHttp();
        check(released[0], "Engine.releaseHttp() releases the network");
        check(e.getHttp() instanceof UrlHttp && e.getHttp() != fake, "after releaseHttp() a new default is created on demand");
        e.setHttp(fake);
        e.setHttp(null);
        check(e.getHttp() instanceof UrlHttp, "setHttp(null) brings the default back");
        e.releaseHttp();

        srv.stop(0);
        System.out.println(failed == 0 ? "ALL HTTP TESTS PASSED" : failed + " HTTP TESTS FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------- https with keytool made certificates

    static void keytool(String... a) throws Exception {
        String[] cmd = new String[a.length + 1];
        cmd[0] = System.getProperty("java.home") + File.separator + "bin" + File.separator + "keytool";
        System.arraycopy(a, 0, cmd, 1, a.length);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream in = p.getInputStream();
        byte[] b = new byte[4096];
        for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        if (p.waitFor() != 0) throw new IOException("keytool failed: " + out);
    }

    static HttpsServer https(File ks) throws Exception {
        KeyStore k = KeyStore.getInstance("PKCS12");
        InputStream in = new FileInputStream(ks);
        k.load(in, "changeit".toCharArray());
        in.close();
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(k, "changeit".toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        HttpsServer s = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.setHttpsConfigurator(new HttpsConfigurator(ctx));
        s.setExecutor(Executors.newCachedThreadPool());
        return s;
    }

    static void httpsTests(HttpServer plain, final String plainBase) throws Exception {
        File dir = Files.createTempDirectory("skarph-https").toFile();
        File good = new File(dir, "good.p12"), other = new File(dir, "other.p12"), cer = new File(dir, "good.cer"), trust = new File(dir, "trust.p12");
        try {
            for (File f : new File[] {good, other}) {
                keytool("-genkeypair", "-alias", "k", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost",
                        "-ext", "san=dns:localhost,ip:127.0.0.1", "-keystore", f.getPath(), "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit");
            }
            keytool("-exportcert", "-alias", "k", "-file", cer.getPath(), "-keystore", good.getPath(), "-storetype", "PKCS12", "-storepass", "changeit");
            keytool("-importcert", "-noprompt", "-alias", "k", "-file", cer.getPath(), "-keystore", trust.getPath(), "-storetype", "PKCS12", "-storepass", "changeit");
        } catch (Exception e) {
            System.out.println("SKIP https tests (no keytool): " + e.getMessage());
            return;
        }
        System.setProperty("javax.net.ssl.trustStore", trust.getPath());
        System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");

        final HttpsServer ok = https(good);
        final HttpsServer untrusted = https(other);
        final String sBase = "https://localhost:" + ok.getAddress().getPort();
        on(ok, "/ok", new H() { public void handle(HttpExchange ex) throws Exception { reply(ex, 200, "text/plain", "secure".getBytes()); }});
        on(ok, "/echo", new H() { public void handle(HttpExchange ex) throws Exception {
            ByteArrayOutputStream in = new ByteArrayOutputStream();
            InputStream is = ex.getRequestBody();
            byte[] buf = new byte[1024];
            for (int n; (n = is.read(buf)) > 0; ) in.write(buf, 0, n);
            reply(ex, 200, "text/plain", in.toByteArray());
        }});
        on(ok, "/to-http", new H() { public void handle(HttpExchange ex) throws Exception { redirect(ex, plainBase + "/ok"); }});
        on(untrusted, "/ok", new H() { public void handle(HttpExchange ex) throws Exception { reply(ex, 200, "text/plain", "evil".getBytes()); }});
        on(plain, "/to-https", new H() { public void handle(HttpExchange ex) throws Exception { redirect(ex, sBase + "/ok"); }});
        ok.start();
        untrusted.start();

        UrlHttp h = fast();
        HttpRequest r = await(h.get(sBase + "/ok"), 8000);
        check(r.isSuccess() && "secure".equals(r.getText()), "https: a trusted certificate works");
        r = await(h.get("https://localhost:" + untrusted.getAddress().getPort() + "/ok"), 8000);
        check(!r.isSuccess() && r.getAttempts() == 1 && r.getError().contains("not trusted") && count("/ok") >= 1, "https: an untrusted certificate fails at once, no repeat (" + r.getError() + ")");
        r = await(h.get(sBase + "/to-http"), 8000);
        check(!r.isSuccess() && r.getError().contains("https to http"), "https: a redirect to plain http is refused");
        r = await(h.get(plainBase + "/to-https"), 8000);
        check(r.isSuccess() && "secure".equals(r.getText()), "http: a redirect to https is followed");
        r = await(h.post(sBase + "/echo", "secret".getBytes(), "text/plain"), 8000);
        check(r.isSuccess() && "secret".equals(r.getText()), "https: POST works");
        ok.stop(0);
        untrusted.stop(0);
    }
}
