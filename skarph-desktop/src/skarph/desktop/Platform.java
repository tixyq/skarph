// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The operating system and CPU the program runs on, and the LWJGL natives classifier that belongs to them. */
public final class Platform {
    public enum Os {
        WINDOWS("windows"), LINUX("linux"), MACOS("macos");

        public final String id;

        Os(String id) {
            this.id = id;
        }
    }

    public enum Arch {
        X64("x64"), X86("x86"), ARM64("arm64"), ARM32("arm32");

        public final String id;

        Arch(String id) {
            this.id = id;
        }
    }

    public final Os os;
    public final Arch arch;
    /** The part of the natives file name after the version, for example "natives-linux-arm64". */
    public final String classifier;

    private Platform(Os os, Arch arch, String classifier) {
        this.os = os;
        this.arch = arch;
        this.classifier = classifier;
    }

    /** For example "windows-x64". */
    public String id() {
        return os.id + "-" + arch.id;
    }

    public static Platform detect() throws SetupException {
        return of(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /** @param osName the value of the system property os.name @param osArch the value of os.arch */
    public static Platform of(String osName, String osArch) throws SetupException {
        Os os = parseOs(osName);
        Arch arch = parseArch(osArch);
        String classifier = os == null || arch == null ? null : classifier(os, arch);
        if (classifier == null) {
            throw new SetupException("This system is not supported: os.name=\"" + osName + "\", os.arch=\"" + osArch
                    + "\". Supported: " + supportedList() + ".");
        }
        return new Platform(os, arch, classifier);
    }

    /** Every supported combination (for the link check and the hash pinning tool). */
    public static List<Platform> all() {
        List<Platform> list = new ArrayList<Platform>();
        for (Os os : Os.values()) {
            for (Arch arch : Arch.values()) {
                String c = classifier(os, arch);
                if (c != null) {
                    list.add(new Platform(os, arch, c));
                }
            }
        }
        return list;
    }

    private static String supportedList() {
        StringBuilder sb = new StringBuilder();
        for (Platform p : all()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(p.id());
        }
        return sb.toString();
    }

    private static Os parseOs(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("win")) {
            return Os.WINDOWS;
        }
        if (n.contains("mac") || n.contains("darwin")) {
            return Os.MACOS;
        }
        if (n.contains("linux")) {
            return Os.LINUX;
        }
        return null;
    }

    private static Arch parseArch(String arch) {
        String a = arch.toLowerCase(Locale.ROOT);
        if (a.equals("amd64") || a.equals("x86_64") || a.equals("x64")) {
            return Arch.X64;
        }
        if (a.equals("x86") || a.equals("i386") || a.equals("i486") || a.equals("i586") || a.equals("i686")) {
            return Arch.X86;
        }
        if (a.equals("aarch64") || a.equals("arm64")) {
            return Arch.ARM64;
        }
        if (a.equals("arm") || a.startsWith("armv") || a.equals("armhf")) {
            return Arch.ARM32;
        }
        return null;
    }

    /** The LWJGL 3.3 natives classifiers. null = LWJGL has no natives for the combination. */
    private static String classifier(Os os, Arch arch) {
        switch (os) {
            case WINDOWS:
                return arch == Arch.X64 ? "natives-windows" : arch == Arch.X86 ? "natives-windows-x86"
                        : arch == Arch.ARM64 ? "natives-windows-arm64" : null;
            case LINUX:
                return arch == Arch.X64 ? "natives-linux" : arch == Arch.ARM64 ? "natives-linux-arm64"
                        : arch == Arch.ARM32 ? "natives-linux-arm32" : null;
            case MACOS:
                return arch == Arch.X64 ? "natives-macos" : arch == Arch.ARM64 ? "natives-macos-arm64" : null;
            default:
                return null;
        }
    }
}
