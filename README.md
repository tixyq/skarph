<p align="center"><img src="icon.png" width="128" alt="Skarph"></p>

# Skarph

A small 3D engine with 2D support, written in Java. Fixed 60 Hz logic, no allocation in the game loop, OpenGL ES 1.x
and 2.0 backends with automatic choice. The core has no platform dependencies; it runs on Android and on Windows,
Linux and macOS through two equal platform layers.

```
skarph-core/      the engine, pure Java: loop, math, scene, physics, UI, effects
skarph-android/   platform layer for Android: view, renderers, audio, storage, assets, input
skarph-desktop/   platform layer for Windows, Linux and macOS: window, OpenGL, audio, input (LWJGL, downloaded on first start)
demos/            example programs (index: demos/README.md)
docs/             MANUAL.md (guide) and API.md (every public class and member)
tools/            Python tools: model converters, lightmap baker, camera tracks, level preview, icons
tests/            headless tests
icon.png          the Skarph icon, 512x512, transparent; used as the icon of every platform
build.sh          compiles the core and both platform layers, runs all tests
build-desktop.sh  builds a portable jar of a program for Windows, Linux and macOS
```

## Start

Read [docs/MANUAL.md](docs/MANUAL.md), section 2, for a working app in one screen of code.

## Build and test

Both platforms are built from the same sources; nothing in the core depends on either of them.

```
./build.sh
```

Compiles the engine and the platform layers and runs the headless tests. Needs a JDK. The Android jars that enable
the extra checks are described at the top of `build.sh`.

Android: add `skarph-core/src` and `skarph-android/src` to the project, see [docs/MANUAL.md](docs/MANUAL.md), section 2.

Desktop: `./build-desktop.sh` builds one jar that runs with `java -jar` on Windows, Linux and macOS (Java 8 or newer).
See [docs/MANUAL.md](docs/MANUAL.md), section 14.

## License

MIT License, see [LICENSE](LICENSE).

Copyright (c) 2026 tixyq
