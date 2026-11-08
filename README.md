<p align="center"><img src="icon.png" width="128" alt="Skarph"></p>

# Skarph

A small 3D engine with 2D support, written in Java. Fixed 60 Hz logic, no allocation in the game loop, OpenGL ES 1.x
and 2.0 backends with automatic choice. The core has no platform dependencies, so it is easy to port.

```
skarph-core/      the engine, pure Java: loop, math, scene, physics, UI, effects
skarph-android/   the platform layer: view, renderers, audio, storage, assets, input
skarph-desktop/   the desktop layer: one portable jar for Windows, Linux and macOS (LWJGL, downloaded on first start)
demos/            example programs (index: demos/README.md)
docs/             MANUAL.md (guide) and API.md (every public class and member)
tools/            Python tools: model converters, lightmap baker, camera tracks, level preview, icons (no arguments = only help)
tests/            headless tests
icon.png          the Skarph icon, 512x512, transparent: the icon of every window and app (desktop jar, Android launcher)
build.sh          compiles, checks API levels, runs all tests
build-desktop.sh  builds the portable desktop jar (the maze game if game/ is there, else the v1.0 demo; or your game)
```

## Start

Read [docs/MANUAL.md](docs/MANUAL.md), section 2, for a working app in one screen of code. The demos in `demos/` show
every feature.

## Build and test

```
./build.sh
```

Needs a JDK. The Android jars that enable the extra checks are described at the top of `build.sh`.

Desktop: `./build-desktop.sh` builds `build/desktop/skarph-demo-v1.0.jar`, run it with `java -jar`. See
[docs/MANUAL.md](docs/MANUAL.md), section 14.

## License

GNU General Public License, version 3 or later, with an attribution term (see [LICENSE](LICENSE)). You may use the code
in commercial projects, but every project that uses it must be released with its full source code under the same license
and must credit the author.

Copyright (C) 2026 tixyq
