# Pulse Music Player

A sleek, lightweight music player client mod built for modern Minecraft (Fabric 1.26+ / 26 and higher) with full Lunar Client compatibility.

<p align="center">
  <img src="assets/icon.png" width="128" height="128" alt="Pulse Music Player Icon" />
</p>

## ⚡ Download
- 📦 **Direct Jar Download:** [**`pulse-music-player-1.5.9.jar`**](https://github.com/bruhslow/pulse-music-player/releases/download/v1.5.9/pulse-music-player-1.5.9.jar)
- 🚀 **Release Page:** [**Pulse Music Player v1.5.9**](https://github.com/bruhslow/pulse-music-player/releases/tag/v1.5.9)

---

## Highlights
- **Dynamic Floating Island HUD**: Sleek pill-shaped capsule with dynamic audio-reactive dancing equalizer bars and track info. Position anywhere on screen.
- **Modern Glassmorphic UI**: Smooth rounded pill buttons, lively equalizer waveform, instant search, and dedicated queue management.
- **Listen Together (100% GUI-Driven Rooms)**: Create and join password-protected or open listening rooms right from the GUI without complex chat commands.
- **Multiplayer Nametag Badges**: Shows `[⚡]` indicator for players with the mod, and an animated beating `[♫]` badge with music notes when listening.
- **High-Performance Audio Engine**: Pure-Java audio decoder supporting MP3, OGG Vorbis, AAC streams, local audio files, web radio, and YouTube music.

## Commands
- `/mp` - Open the Pulse player interface
- `/mp play <song/url>` - Stream music or search catalog
- `/mp pause` - Pause or resume playback
- `/mp stop` - Stop audio playback
- `/mp next` - Skip to next song in queue
- `/mp prev` - Return to previous song
- `/mp queue` - View upcoming queued tracks
- `/mp hud move` - Drag and position the in-game HUD

## Installation

### Client Installation
1. Download [**`pulse-music-player-1.5.9.jar`**](https://github.com/bruhslow/pulse-music-player/releases/download/v1.5.9/pulse-music-player-1.5.9.jar).
2. Drop it into your Minecraft or Lunar Client `.minecraft/mods` folder.
3. Requires **Fabric Loader** and **Fabric API** for Minecraft 1.26+.

### Server-Side Installation (Optional for Multiplayer)
Drop `pulse-music-player-1.5.9.jar` into your Fabric server's `mods/` directory.
- Enables native, zero-latency server packet routing for synced listening rooms.
- Server directly synchronizes audio track position, play/pause states, and room membership.
- *Note: If a server does not have the mod installed, Pulse automatically falls back to seamless peer-to-peer cloud rooms!*

## Author
Developed by **bruhslow**.
