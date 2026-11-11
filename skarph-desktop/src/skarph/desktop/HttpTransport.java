// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.desktop;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;

/** Transport on java.net: timeouts, redirects (also from http to https), only status 200 counts as success. */
public final class HttpTransport implements Transport {
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MAX_REDIRECTS = 5;

    public Response open(String url) throws IOException {
        String current = url;
        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            URLConnection c = new URL(current).openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestProperty("User-Agent", "skarph-desktop");
            if (!(c instanceof HttpURLConnection)) {
                return new Response(c.getInputStream(), c.getContentLength()); // file: URL (a local mirror)
            }
            HttpURLConnection h = (HttpURLConnection) c;
            h.setInstanceFollowRedirects(false);
            int code = h.getResponseCode();
            if (code == 200) {
                long len = h.getContentLength();
                return new Response(h.getInputStream(), len);
            }
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = h.getHeaderField("Location");
                h.disconnect();
                if (loc == null) {
                    throw new IOException("HTTP " + code + " without Location");
                }
                current = new URL(new URL(current), loc).toString();
                continue;
            }
            h.disconnect();
            throw new IOException("HTTP " + code);
        }
        throw new IOException("too many redirects");
    }
}
