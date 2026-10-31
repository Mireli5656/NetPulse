# NetPulse

A lightweight Android app for measuring network quality: real ICMP latency, jitter and packet loss, plus a DNS benchmark that measures real DNS query time separately from ping.

> **Status: v0.3.0 (latest release).** Core ping monitoring works. The DNS benchmark is already in `main` and will ship in the next release. See the [roadmap](#roadmap) for what is still missing.

## Features

### Available in v0.3.0

- Live ICMP ping monitor with Start / Stop
- Selectable ping interval: 0.2 s (default), 0.5 s or 1 s
- Steady ping rhythm: the interval is counted from the start of one ping to the start of the next
- Optional "Keep Wi-Fi awake while testing" switch (off by default)
- Latency, minimum / average / maximum, jitter and packet loss (rolling window of the last 300 samples)
- Material Design 3 interface with light and dark themes that follow the system setting
- Test survives screen rotation and stops automatically when the app leaves the foreground
- Host name and IP address validation

### Added since v0.3.0 (in `main`, coming in the next release)

- DNS benchmark screen for Cloudflare, Google, Quad9 and Control D
- Cached and uncached lookup tests, with the fastest server highlighted after a full run
- Results per server: average, minimum and maximum response time, and failed queries

### Roadmap

- Your current DNS server ("System DNS") in the benchmark
- Custom DNS server addresses
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

### Ping

NetPulse runs the device's system `ping` tool inside the app and reads the round-trip time from each reply. Packets that get no reply within 2 seconds count as lost. Jitter is the mean absolute difference between consecutive successful round-trip times.

Some devices restrict the system `ping` tool. In that case the app shows an error message instead of results.

### Ping interval and Wi-Fi power saving

Wi-Fi power saving can put the radio to sleep between widely spaced packets, which can make latency look higher and less stable. For this reason NetPulse pings every 0.2 seconds by default. You can switch to 0.5 s or 1 s to compare the results on your own device. How strong the effect is depends on the device and the access point.

### Keep Wi-Fi awake

The optional switch asks Android to keep Wi-Fi out of power saving during a test. On Android 10 and newer the system only honors it while the screen is on and NetPulse is in the foreground. On Android 8 and 9 the older high-performance lock is used. Results then show best-case latency rather than what an idle phone typically sees, so it is worth comparing both. The lock is released when the test stops or the app leaves the foreground, and it uses a little more battery.

### DNS benchmark (in `main`)

The benchmark sends real DNS queries (an `A` record request over UDP, port 53) and measures the time from sending the query to receiving the matching answer. This is independent of ICMP ping.

- Servers are tested one after another, not in parallel, so they do not disturb each other.
- Each server first gets 3 warm-up queries. They wake the Wi-Fi radio and fill the server's cache, and they are never counted.
- Then 10 queries are sent, 0.2 seconds apart.
- **Cached** asks for the same popular name every time (`example.com`), so it shows the fastest possible answer.
- **Uncached** asks for a different random 16-character name directly under `.com` each time (for example `a1b2c3d4e5f60718.com`). Such a name does not exist, so the server cannot answer from memory and has to ask a `.com` server. The answer is NXDOMAIN (name does not exist). That is still a real response and counts as a success.
- Timeouts, network errors and server errors (such as SERVFAIL) count as failed queries.
- After a full run the servers are sorted by average time, and the fastest is highlighted.

Good to know:

- The uncached result is an approximation. Servers may treat names that do not exist differently from real lookups.
- The test uses classic unencrypted DNS on port 53. A network that intercepts or redirects DNS traffic can change the results.
- "No answer" means the server did not reply to any query. Your network may block DNS traffic to that address, so this does not always mean the server itself is down.

## Privacy

- Three permissions: `INTERNET`, `ACCESS_NETWORK_STATE` and `WAKE_LOCK`. No location, contacts or storage access.
- `WAKE_LOCK` is used only to keep Wi-Fi awake during a test, and only if you turn that option on.
- No accounts, ads, analytics or tracking libraries.
- Ping packets go only to the host you enter (default `1.1.1.1`).
- The DNS benchmark (in `main`) sends queries for `example.com` and random `.com` names directly to Cloudflare (`1.1.1.1`), Google (`8.8.8.8`), Quad9 (`9.9.9.9`) and Control D (`76.76.2.0`). Like any DNS query, these servers can see your IP address and the names you ask for, and they may ask other DNS servers (such as `.com` servers) to answer. Nothing else is sent.
- Nothing is uploaded elsewhere. Backups are disabled and cleartext HTTP traffic is blocked.

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
├── domain/        ping and DNS models, statistics
├── network/       ping engine, DNS engine and benchmark, Wi-Fi lock helper
└── ui/
    ├── monitor/   ping monitor state and logic
    └── dns/       DNS benchmark screen, state and list adapter
```

## Building

The project is built entirely on GitHub Actions, so no local setup is needed.

- Every push to `main` runs the unit tests and builds a debug APK. Open the run under the **Actions** tab and download the `NetPulse-debug-apk` artifact.
- Publishing a GitHub release (for example with the tag `v0.3.0`) builds the APK and attaches it to that release automatically.

## License

Released under the [MIT License](LICENSE).
