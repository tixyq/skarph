#!/usr/bin/env python3
# Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
"""Writes skarph-desktop/res/skarph/desktop/lwjgl-VERSION.sha1: the SHA-1 of every LWJGL file for every supported system.

Needs a network. Takes the list of files from the program itself (java skarph.desktop.PrintUrls), downloads each file,
computes its SHA-1 and checks it against the .sha1 that Maven Central publishes. Run it once after changing
Libraries.VERSION, commit the result.   usage: tools/pin_lwjgl.py CLASSES_DIR   (build/desktop/classes after ./build-desktop.sh)
Without arguments (or -h, -help, --help) it only prints this help.
"""
import hashlib, os, subprocess, sys, urllib.request

root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
if len(sys.argv) != 2 or sys.argv[1] in ("-h", "-help", "--help"):
    print(__doc__)
    sys.exit(0 if len(sys.argv) == 1 or sys.argv[1] in ("-h", "-help", "--help") else 2)
classes = sys.argv[1]
lines = subprocess.check_output(["java", "-cp", classes, "skarph.desktop.PrintUrls"], text=True).split()
version = None
out = []
for url in lines:
    name = url.rsplit("/", 1)[1]
    version = url.rsplit("/", 2)[1]
    data = urllib.request.urlopen(url, timeout=60).read()
    got = hashlib.sha1(data).hexdigest()
    published = urllib.request.urlopen(url + ".sha1", timeout=60).read().decode().split()[0].lower()
    if got != published:
        sys.exit("%s: downloaded sum %s, published sum %s" % (name, got, published))
    out.append("%s  %s" % (got, name))
    print("ok", name)
path = os.path.join(root, "skarph-desktop", "res", "skarph", "desktop", "lwjgl-%s.sha1" % version)
with open(path, "w") as f:
    f.write("# SHA-1 sums of the LWJGL %s files, written by tools/pin_lwjgl.py\n" % version)
    f.write("\n".join(sorted(out)) + "\n")
print("wrote", path)
