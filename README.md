<h1 align="center"><b>Fluxio</b></h1>
<p align="center">
  <img src="assets/icon.png" width="120" alt="Fluxio icon">
</p>
<h4 align="center">A powerful, private music player for Android.</h4>
<p align="center">
    <a href="https://github.com/Anasimandro10/Fluxio/releases/">
        <img alt="Latest Version" src="https://img.shields.io/static/v1?label=tag&message=v4.0.10&color=64B5F6&style=flat">
    </a>
    <a href="https://www.gnu.org/licenses/gpl-3.0">
        <img src="https://img.shields.io/badge/license-GPL%20v3-2B6DBE.svg?style=flat">
    </a>
    <img alt="Minimum SDK Version" src="https://img.shields.io/badge/API-24%2B-1450A8?style=flat">
</p>

## About

Fluxio is a fork of [Auxio](https://github.com/oxygencobalt/Auxio) — a fast, reliable local music player for Android — extended with features the original intentionally omits: a built-in equalizer, synchronized lyrics, listening statistics, and a redesigned UI.

No account. No telemetry. No ads. It plays music.

## Features

### Inherited from Auxio
- Playback based on [Media3 ExoPlayer](https://developer.android.com/guide/topics/media/exoplayer)
- Snappy UI derived from the latest Material Design guidelines
- Support for disc numbers, multiple artists, release types, precise dates, sort tags, and more
- Advanced artist system that unifies artists and album artists
- SD Card-aware folder management
- Reliable playlisting functionality
- Playback state persistence
- Android Auto support
- Automatic gapless playback
- Full ReplayGain support (MP3, FLAC, OGG, OPUS, MP4)
- Edge-to-edge UI
- Embedded covers support
- Search functionality
- Headset autoplay
- Stylish widgets
- Completely private and offline

### Added in Fluxio

**🎧 Audio**
- Built-in 10-band equalizer (biquad filters via AudioProcessor)
- Stereo Widening effect (Mid-Side processing)
- Volume normalization for files without ReplayGain tags
- Audio offload mode (battery saving + hardware quality on compatible devices)
- Smooth crossfade between tracks

**🎵 Lyrics**
- Embedded lyrics from tags (ID3 USLT, Vorbis LYRICS, MP4 ©lyr)
- Local LRC file support (same filename as audio file)
- [LRCLIB](https://lrclib.net) as optional fallback (opt-in, cached locally)
- Line-by-line highlighting (standard LRC)
- Word-by-word highlighting à la Apple Music (extended LRC with word timestamps)
- Immersive fullscreen lyrics sheet

**🎨 UI**
- Full visual redesign with dynamic color extracted from album art (Palette API)
- Subtle album-tinted background (dark + desaturated, ~4-6% opacity)
- Soft radial glow at the top of the player screen
- Album art as the centerpiece of the player
- Typography: Inter
- Smooth animations (400–600ms), color transitions (800ms)
- Grid or list view toggle in library
- Improved Android Auto layout with larger buttons

**📊 Statistics**
- Listening history stored locally (song, artist, album, timestamp, seconds listened)
- Stats tab in the main navigation
- Top songs, artists, and albums per period: week / month / year / all time
- Total listening time as the hero metric

## Privacy

- No account required
- No telemetry of any kind
- No ads
- LRCLIB is opt-in and only sends: artist, song title, album, duration

## Permissions

- `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE` — to read and play your music files
- `FOREGROUND_SERVICE`, `WAKE_LOCK` — to keep music playing in the background
- `POST_NOTIFICATION` — to show playback and loading notifications
- `INTERNET` — only used if LRCLIB lookup is enabled in settings

## Building

Fluxio uses GitHub Actions for automated builds. Every push to the `dev` branch compiles a debug APK automatically — no local build environment needed.

To download the latest build:
1. Go to the [Actions tab](https://github.com/Anasimandro10/Fluxio/actions)
2. Open the latest successful run
3. Download the `Fluxio_Debug` artifact

### Build requirements (local)
- `cmake` and `ninja-build` must be installed
- Clone with submodules: `git clone --recurse-submodules`
- JDK 21
- NDK version `28.2.13676358`
- Building on Windows is **not supported** — use GitHub Actions instead

## License

[![GNU GPLv3 Image](https://www.gnu.org/graphics/gplv3-127x51.png)](http://www.gnu.org/licenses/gpl-3.0.en.html)

Fluxio is a fork of Auxio and inherits its license. This software is Free Software: you can use, study, share, and improve it under the terms of the [GNU General Public License v3](https://www.gnu.org/licenses/gpl-3.0.en.html) or later.

Original Auxio project: [github.com/oxygencobalt/Auxio](https://github.com/oxygencobalt/Auxio)
