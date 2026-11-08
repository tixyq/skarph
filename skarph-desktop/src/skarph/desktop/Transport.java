// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.desktop;

import java.io.IOException;
import java.io.InputStream;

/** Opens a URL for reading. The program uses HttpTransport; tests use a fake. */
public interface Transport {
    final class Response {
        public final InputStream in;
        /** Size in bytes, or -1 if the server did not say. */
        public final long length;

        public Response(InputStream in, long length) {
            this.in = in;
            this.length = length;
        }
    }

    /** @throws IOException for every failure, including an HTTP status other than 200 */
    Response open(String url) throws IOException;
}
