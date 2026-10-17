<div align="center">

# PocketSite

**Pocket-sized static site manager for Android**

Multi-site · Independent ports · One-tap serve · Fully offline

[![Release](https://img.shields.io/github/v/release/Samge0/PocketSite?style=flat-square)](https://github.com/Samge0/PocketSite/releases)
[![Min SDK](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-MIT-5EEAD4?style=flat-square)](LICENSE)

中文 | English

</div>

---

> 🌐 **[Project page](https://samge0.github.io/PocketSite/)** — feature overview, quick start, and download links on one page
>
> ⬇ **[Download APK (recommended)](https://github.com/Samge0/PocketSite/releases/latest)** — grab the CI-signed APK from Releases (e.g. `PocketSite-v1.x.x.apk`, ~6.3MB), no build required.

---

> 📝 This file is the English mirror of the Chinese README (which is the canonical, always
> up-to-date version). Feature set is identical; wording may lag slightly behind.

PocketSite runs an embedded HTTP server on your phone and puts multiple static sites in your
pocket. Every site gets its own port, its own switch, and sites can run in parallel. The
in-app WebView preview works offline; no Termux, no root required.

## Why PocketSite

Most "local server" apps on Android are wrappers around a shell environment — heavy to install,
awkward to drive. PocketSite is a single native app: pick a ZIP, tap a switch, and your site is
live at `http://127.0.0.1:<port>`. It exists for the moment you need to show a real, working
site with no network around.

- **No Termux, no root, no network** — one APK, everything embedded
- **Multi-site, parallel** — every site is an isolated server on its own port; run as many as you like
- **Survives airplane mode** — the loopback interface never goes down

## Features

| | |
|---|---|
| 🚀 **One-tap serve** | Foreground service keeps sites alive; pulsing status light shows server state at a glance |
| 🗂 **Site library** | Letter-avatar tiles with live port, running state and quick actions: in-app preview / system browser / copy URL |
| 📦 **Deploy anywhere** | Import a local ZIP or fetch from a URL with download progress; illustrated package-format guide built in |
| 🔀 **Hot content update** | "Update package" overwrites content in place — port and settings survive |
| 🛡 **Hardened server** | Hand-rolled HTTP/1.1 in pure Kotlin: SPA fallback, HTTP Range streaming (video seeking), path-traversal guard, correct MIME for 30+ types |
| 📁 **Movable storage** | Site root can live in app-private storage or any user directory; migration is copy → byte-verify → atomic swap with rollback on failure |
| ⚡ **Autostart** | Flag sites to start automatically when the app opens |
| 🔒 **Safe exits** | External links open with a confirm dialog (mute-able in settings) |

## Site package format

A standard ZIP with `index.html` at its root:

```
site.zip
├── index.html        ← required entry
├── css/ …
├── js/ …
└── images/ …
```

A single wrapping folder containing `index.html` also works — it is auto-promoted to the site
root. A full illustrated guide lives inside the app (**Import → ?**), and sample site packages
ship with every [release](https://github.com/Samge0/PocketSite/releases).

## Getting started

1. Grab the latest APK from [Releases](https://github.com/Samge0/PocketSite/releases) and install it
2. Import a site: **＋ → Import ZIP** (or fetch from a URL)
3. Flip the switch on the site card — the server is live on its assigned port
4. Tap 🌐 to preview inside the app, or ↗ to open in your browser

> **Offline tip**: use the in-app 🌐 preview when there's no network. Some mobile browsers
> run a connectivity pre-check and refuse to load `127.0.0.1` in airplane mode; the app's
> WebView doesn't.

Computers on the same Wi-Fi can also reach your sites at `http://<phone-ip>:<port>/`.

## Build

Requirements: JDK 17+, Android SDK (compileSdk 35).

```bash
git clone https://github.com/Samge0/PocketSite.git
cd PocketSite
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleRelease      # signed release (see Signing below)
./gradlew assembleDebug        # or debug build
```

### Signing

Releases are signed via CI secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`). For local builds, place a keystore at `app/pocketsite-release.jks` and the
matching credentials in `.secrets/*.txt` — the build picks up whichever source is present
(CI env vars take priority). Unsigned fallback is debug-signed.

## Tech

| | |
|---|---|
| Language / UI | Kotlin 2.0.21 · Jetpack Compose (Material 3) |
| Build | Gradle 8.13 · AGP 8.7.3 · minSdk 26 · targetSdk 35 |
| Runtime dependencies | **None** — the HTTP server, ZIP engine and downloader are all hand-rolled on the Kotlin stdlib |

## Project layout

```
app/src/main/java/com/samge/pocketsite/
├── MainActivity.kt        # navigation host + home/detail/settings screens
├── StaticSiteServer.kt    # embedded HTTP server (SPA fallback, Range, traversal guard)
├── SiteServerService.kt   # foreground service hosting one server instance per site
├── SitesRepository.kt     # site registry: import/update/delete/port allocation
├── Migrator.kt            # storage migration (copy → verify → atomic swap, rollback-safe)
├── SitePreviewActivity.kt # in-app preview (WebView + toolbar + external-link guard)
├── GithubMark.kt          # official GitHub vector mark
└── Theme.kt               # Material 3 dark theme
```

## FAQ

<details>
<summary><b>Why can't my browser open 127.0.0.1 when offline?</b></summary>

The server is fine — the loopback interface never goes down. Many vendor browsers (OPPO,
vivo, Xiaomi, Huawei…) run a connectivity pre-check and show their own "no network" page
before the request ever reaches `127.0.0.1`. Use the in-app 🌐 preview instead; it talks to
the server directly with no pre-check.
</details>

<details>
<summary><b>Do ports conflict?</b></summary>

No. Each site gets a random port in 8000–9999 that is checked against other sites' ports
<i>and</i> probed on the device at allocation time. You can change a port in the site's
detail page (site must be stopped).
</details>

<details>
<summary><b>Where are my sites stored?</b></summary>

App-private storage by default (survives app updates, removed on uninstall). Switch to a
user directory in **Settings → 站点根目录** to keep sites across reinstalls — migration is
automatic and rollback-safe.
</details>

## License

[MIT](LICENSE)
