// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.desktop;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Downloads one library file and accepts it only if its SHA-1 sum is right. The right sum is the pinned one when there is
 * one, otherwise the sum that the Maven repository publishes next to the file (the file name plus .sha1). The bytes may
 * come from any mirror; a mirror that is down, slow or serves something else is skipped and the next one is tried.
 */
final class Downloader {
    static final String OFFLINE_HINT = "\nThe first start needs the internet once. Without it: put the library files into a folder named"
            + " skarph-libs next to the program jar (or use -Dskarph.libs=FOLDER). java -jar PROGRAM.jar --check-links lists the addresses.";

    private final Transport transport;
    private final List<String> mirrors;
    private final Map<String, String> pins;
    private final int attempts;
    private final long retryDelayMs;

    Downloader(Transport transport, List<String> mirrors, Map<String, String> pins, int attempts, long retryDelayMs) {
        this.transport = transport;
        this.mirrors = mirrors;
        this.pins = pins;
        this.attempts = attempts < 1 ? 1 : attempts;
        this.retryDelayMs = retryDelayMs;
    }

    /** The sum the file must have. */
    String expectedSha1(Libraries.Artifact a, StringBuilder log) throws SetupException {
        String pinned = pins.get(a.fileName());
        if (pinned != null) {
            return pinned;
        }
        for (String mirror : mirrors) {
            String url = a.url(mirror) + ".sha1";
            try {
                Transport.Response r = transport.open(url);
                try {
                    byte[] buf = new byte[256];
                    int n = r.in.read(buf);
                    String text = n > 0 ? new String(buf, 0, n, "US-ASCII").trim() : "";
                    String first = text.split("\\s+")[0].toLowerCase(Locale.ROOT);
                    if (Pins.isSha1(first)) {
                        return first;
                    }
                    log.append("\n  ").append(url).append(": not a SHA-1 sum");
                } finally {
                    r.in.close();
                }
            } catch (IOException e) {
                log.append("\n  ").append(url).append(": ").append(why(e));
            }
        }
        throw new SetupException("Could not get the checksum of " + a + "." + log + OFFLINE_HINT);
    }

    /** Writes the verified file to dest (through a .part file). */
    void download(Libraries.Artifact a, String sha1, File dest, Progress progress) throws SetupException {
        StringBuilder log = new StringBuilder();
        File part = new File(dest.getPath() + ".part");
        for (String mirror : mirrors) {
            String url = a.url(mirror);
            for (int attempt = 1; attempt <= attempts; attempt++) {
                try {
                    String got = fetch(url, part, progress);
                    if (got.equals(sha1)) {
                        if (dest.exists() && !dest.delete()) {
                            throw new IOException("cannot replace " + dest);
                        }
                        if (!part.renameTo(dest)) {
                            throw new IOException("cannot rename " + part + " to " + dest);
                        }
                        return;
                    }
                    log.append("\n  ").append(url).append(": wrong checksum (got ").append(got).append(")");
                    break; // the same mirror will serve the same bytes: next mirror
                } catch (IOException e) {
                    log.append("\n  ").append(url).append(": ").append(why(e));
                    if (attempt < attempts && retryDelayMs > 0) {
                        try {
                            Thread.sleep(retryDelayMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new SetupException("Interrupted.");
                        }
                    }
                }
            }
        }
        part.delete();
        throw new SetupException("Could not download " + a + " from any server:" + log + OFFLINE_HINT);
    }

    private String fetch(String url, File part, Progress progress) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("no SHA-1 in this Java");
        }
        Transport.Response r = transport.open(url);
        InputStream in = r.in;
        OutputStream out = null;
        try {
            out = new FileOutputStream(part);
            byte[] buf = new byte[16384];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
                out.write(buf, 0, n);
                done += n;
                progress.progress(done, r.length);
            }
            if (r.length >= 0 && done != r.length) {
                throw new IOException("cut off after " + done + " of " + r.length + " bytes");
            }
        } finally {
            in.close();
            if (out != null) {
                out.close();
            }
        }
        return hex(md.digest());
    }

    /** A short reason for the person: our own messages as they are, system errors with their kind (UnknownHostException...). */
    static String why(IOException e) {
        String m = e.getMessage();
        if (e.getClass() == IOException.class && m != null) {
            return m;
        }
        return e.getClass().getSimpleName() + (m != null ? ": " + m : "");
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        }
        return sb.toString();
    }

    static String sha1Of(File f) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("no SHA-1 in this Java");
        }
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return hex(md.digest());
    }
}
