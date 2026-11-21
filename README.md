# Pulse Music Player

A sleek, lightweight music player client mod built for modern Minecraft (Fabric 1.21.x & 26.x).

<p align="center">
  <img src="assets/icon.png" width="128" height="128" alt="Pulse Music Player Icon" />
</p>

## ⚡ Direct Downloads (v1.5.9)
Choose the version that matches your Minecraft installation:

- 📦 **Minecraft 1.21.x:** [**`pulse-music-player-1.5.9-mc1.21.jar`**](https://github.com/bruhslow/pulse-music-player/releases/download/v1.5.9/pulse-music-player-1.5.9-mc1.21.jar)
- 📦 **Minecraft 26.x+:** [**`pulse-music-player-1.5.9-mc26.jar`**](https://github.com/bruhslow/pulse-music-player/releases/download/v1.5.9/pulse-music-player-1.5.9-mc26.jar)
- 🚀 **Release Details:** [**Pulse Music Player v1.5.9 Release Page**](https://github.com/bruhslow/pulse-music-player/releases/tag/v1.5.9)

---

## Highlights
- **Dynamic Floating HUD**: Compacts into an animated island widget with real-time waveform bars. Drag and place anywhere on screen.
- **Listen Together**: Create and join peer-to-peer synced audio sessions with friends without requiring any server-side plugins.
- **Multiplayer Nametag Indicators**: Animated beating music badges above player nametags visible to anyone who enables them.
- **Queue & Search**: Fast search, playback queue, local audio file loading, and YouTube/radio streaming.

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
1. Download the JAR for your version (**1.21.x** or **26.x**).
2. Drop it into your Minecraft or Lunar Client `.minecraft/mods` folder.
3. Requires **Fabric Loader** and **Fabric API**.

### Server-Side Installation (Optional for High-Performance Multiplayer)
Drop `serverside-music-player-1.5.9.jar` into your Fabric server's `mods/` directory.
- Enables native, zero-latency server packet routing for synced listening rooms.
- Server directly synchronizes audio track position, play/pause states, and room membership.

## Author
Developed by **bruhslow**.
