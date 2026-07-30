# IPTV Player

Android TV / Fire TV IPTV player with support for M3U, Xtream Codes, Stalker MAC, and Stalker Proxy.

## Features

- **M3U URL** - Load playlists from HTTP/HTTPS URLs
- **Xtream Codes** - Server + Username + Password
- **Stalker MAC** - Portal handshake with MAC authentication
- **Stalker Proxy** - Share your Stalker subscription with other devices via Firebase (no VPS needed)
- **EPG** - Automatic EPG loading and matching
- **Proxy support** - Auto proxy scanner, custom DNS, Warp proxy
- **Multi-language** - Italian, English, German
- **Cloud sync** - Send playlists between devices via Firebase
- **Themes** - Red, Blue, Green, Purple, Orange

## Stalker Proxy

1. Add a Stalker portal (+ icon → Stalker MAC tab)
2. Load channels
3. Press **PRX** button → becomes a proxy provider
4. Other devices see your proxy in their playlist list (☰) automatically

The proxy uses Firebase RTDB to relay `create_link` resolution requests. Device A keeps the session alive and resolves URLs on demand for other clients.

## Build

```bash
.\build_apk.ps1
```

Requires: Windows, PowerShell 7+. Downloads JDK 17, Android SDK, and Gradle automatically on first run.

## Downloads

Latest APK: https://github.com/gbclient/iptv-updates/releases

## License

This software is provided "as is", without warranty of any kind.
