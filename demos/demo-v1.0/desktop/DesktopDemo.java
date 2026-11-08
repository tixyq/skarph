// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.demo.v10;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

import skarph.core.Engine;
import skarph.core.Screen;
import skarph.desktop.Game;
import skarph.desktop.GameConfig;

/** Desktop start of the demo (Windows, Linux, macOS): the same PortDemoScreen as on Android, OpenGL ES 2.0 backend only. */
public final class DesktopDemo implements Game, BackendSwitcher {
    public GameConfig config() {
        GameConfig c = new GameConfig();
        c.title = "Skarph demo v1.0";
        c.width = 1280;
        c.height = 720;
        c.appName = "skarph-demo-v10";
        return c;
    }

    public Screen createScreen(Engine engine) {
        return new PortDemoScreen(this);
    }

    public boolean isGles2Available() {
        return true;
    }

    /** The desktop has one backend (the ES 2.0 renderer on OpenGL 2.1+), so there is nothing to switch. */
    public void switchBackend(int backend) {
    }

    public int getGcCount() {
        long total = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            long n = b.getCollectionCount();
            if (n > 0) {
                total += n;
            }
        }
        return (int) total;
    }
}
