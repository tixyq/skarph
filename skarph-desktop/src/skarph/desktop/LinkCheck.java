// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.desktop;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;

/**
 * Asks every mirror for every file of every supported system (and for its .sha1 file). Run it with
 * java -jar PROGRAM.jar --check-links. The result is 0 when each file can be had from at least one mirror.
 */
public final class LinkCheck {
    private LinkCheck() {
    }

    public static int run(Transport transport, List<String> mirrors, PrintStream out) {
        int missing = 0;
        int partial = 0;
        List<Libraries.Artifact> all = Libraries.everything();
        for (Libraries.Artifact a : all) {
            int ok = 0;
            StringBuilder why = new StringBuilder();
            for (String mirror : mirrors) {
                String problem = probe(transport, a.url(mirror));
                if (problem == null) {
                    problem = probe(transport, a.url(mirror) + ".sha1");
                }
                if (problem == null) {
                    ok++;
                } else {
                    why.append("\n      ").append(mirror).append(": ").append(problem);
                }
            }
            if (ok == 0) {
                missing++;
                out.println("MISSING " + a + why);
            } else if (ok < mirrors.size()) {
                partial++;
                out.println("partial " + a + " (" + ok + " of " + mirrors.size() + " mirrors)" + why);
            } else {
                out.println("ok      " + a);
            }
        }
        out.println(all.size() + " files, " + missing + " unreachable, " + partial + " not on every mirror");
        return missing == 0 ? 0 : 1;
    }

    private static String probe(Transport transport, String url) {
        try {
            Transport.Response r = transport.open(url);
            r.in.close();
            return null;
        } catch (IOException e) {
            return Downloader.why(e);
        }
    }
}
