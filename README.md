# NetPulse

A lightweight Android app for measuring network quality: real ICMP latency, jitter and packet loss. DNS benchmarking is on the roadmap.

> **Status: v0.2.0.** Core ping monitoring works. Other features are still in development, see the [roadmap](#roadmap).

## Features

### Available in v0.2.0

- Live ICMP ping monitor with Start / Stop
- Selectable ping interval: 0.2 s (default), 0.5 s or 1 s
- Steady ping rhythm: the interval is counted from the start of one ping to the start of the next
- Latency, minimum / average / maximum, jitter and packet loss (rolling window of the last 300 samples)
- Material Design 3 interface with light and dark themes that follow the system setting
- Test survives screen rotation and stops automatically when the app leaves the foreground
- Host name and IP address validation

### Added since v0.2.0 (in `main`, coming in the next release)

- Optional "Keep Wi-Fi awake while testing" switch (off by default)

### Roadmap

- DNS benchmark for Cloudflare, Google, Quad9, Control D and your current DNS, with cached and uncached lookups, measured separately from ICMP ping
- DNS leak test (opt-in, because it sends DNS queries to third-party services)
- Interactive latency charts
- Test history (Room) and CSV export
- Settings (DataStore)
- Release builds signed with a permanent key

## Download

1. Open the [Releases](../../releases) page.
2. Download the `.apk` file from the newest release. The APK is attached a few minutes after a release is published, once the build finishes.
3. Open it on your phone and allow installation when Android asks.

Requires Android 8.0 (API 26) or newer.

Builds are currently signed with a debug key. Each new version may therefore need the previous one to be uninstalled first. A permanent signing key is planned.

## How it works

NetPulse runs the device's system `ping` tool inside the app and reads the round-trip time from each reply. Packets that get no reply within 2 seconds count as lost. Jitter is the mean absolute difference between consecutive successful round-trip times.

Some devices restrict the system `ping` tool. In that case the app shows an error message instead of results.

### Ping interval and Wi-Fi power saving

Wi-Fi power saving can put the radio to sleep between widely spaced packets, which can make latency look higher and less stable. For this reason NetPulse pings every 0.2 seconds by default. You can switch to 0.5 s or 1 s to compare the results on your own device. How strong the effect is depends on the device and the access point.

### Keep Wi-Fi awake (in `main`)

The optional switch asks Android to keep Wi-Fi out of power saving during a test. On Android 10 and newer the system only honors it while the screen is on and NetPulse is in the foreground. On Android 8 and 9 the older high-performance lock is used. Results then show best-case latency rather than what an idle phone typically sees, so it is worth comparing both. The lock is released when the test stops or the app leaves the foreground, and it uses a little more battery.

## Privacy

- Released builds (v0.2.0) use only two permissions: `INTERNET` and `ACCESS_NETWORK_STATE`. No location, contacts or storage access.
- The development version in `main` also declares `WAKE_LOCK`. It is used only to keep Wi-Fi awake during a test, and only if you turn that option on.
- No accounts, ads, analytics or tracking libraries.
- Ping packets go only to the host you enter (default `1.1.1.1`).
- Nothing is uploaded. Backups are disabled and cleartext HTTP traffic is blocked.

## Tech stack

- Kotlin, XML layouts with ViewBinding, Material Design 3
- MVVM with Kotlin Coroutines and StateFlow
- Room and DataStore (already included as dependencies, used in upcoming stages)
- Gradle 8.9, Android Gradle Plugin 8.7.3, Kotlin 2.0.21, JDK 17
- compileSdk / targetSdk 35, minSdk 26

## Project structure

```
app/src/main/java/com/netpulse/app/
├── MainActivity.kt
├── domain/        ping models and statistics
├── network/       ping engine and Wi-Fi lock helper
└── ui/monitor/    monitor screen state and logic
```

## Building

The project is built entirely on GitHub Actions, so no local setup is needed.

- Every push to `main` runs the unit tests and builds a debug APK. Open the run under the **Actions** tab and download the `NetPulse-debug-apk` artifact.
- Publishing a GitHub release (for example with the tag `v0.2.0`) builds the APK and attaches it to that release automatically.
- 
