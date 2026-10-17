<div align="center">

# PocketSite

**Pocket-sized static site manager for Android**

Run multiple static sites on your phone with an embedded HTTP server —
each site gets its own port, its own switch, all fully offline.

[![Release](https://img.shields.io/github/v/release/Samge0/PocketSite?style=flat-square)](https://github.com/Samge0/PocketSite/releases)
[![Min SDK](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-MIT-5EEAD4?style=flat-square)](LICENSE)

*English | [中文](#中文说明)*

</div>

---

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
| 📦 **Deploy anywhere** | Import a local ZIP or fetch from a URL with download progress; package-format guide built in |
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
root. A full illustrated guide lives inside the app (**Import → ?**), and a sample site ships
with every [release](https://github.com/Samge0/PocketSite/releases) (`sample-site.zip`).

## Getting started

1. Grab the latest APK from [Releases](https://github.com/Samge0/PocketSite/releases) and install it
2. Import a site: **＋ → 从本地 ZIP 导入** (or fetch from a URL)
3. Flip the switch on the site card — the server is live on its assigned port
4. Tap 🌐 to preview inside the app, or ↗ to open in your browser

> **Offline tip**: use the in-app 🌐 preview when there's no network. Some mobile browsers
> run a connectivity pre-check and refuse to load `127.0.0.1` in airplane mode; the app's
> WebView doesn't.

Computers on the same Wi-Fi can also reach your sites at `http://<phone-ip>:<port>/` — handy
for quick demos on a bigger screen.

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

---

<div align="center">

[中文说明](#中文说明) · [Releases](https://github.com/Samge0/PocketSite/releases) · [MIT License](LICENSE)

</div>

---

<a name="中文说明"></a>
## 中文说明

PocketSite 是一个手机端的静态站点管理器：内嵌 HTTP 服务器，把多个静态站点装进口袋。
每个站点独立端口、独立开关、可同时运行；应用内 WebView 预览断网可用，全程无需 Termux 或 root。

**功能**：多站点并行（独立端口 + 冲突检测）· 一键启停（前台服务保活）· ZIP/URL 两种部署方式 ·
内容热更新（保留端口与设置）· 应用内预览（离线可用）· 站点根目录迁移（复制→校验→原子替换，可回滚）·
随应用启动 · 外链跳转确认。

**使用**：安装 APK → 导入站点 ZIP（或 URL 拉取）→ 打开站点开关 → 🌐 应用内预览或 ↗ 系统浏览器访问
`http://127.0.0.1:<端口>/`。同一局域网内的电脑可通过手机 IP 直接访问。

**站点包格式**：标准 ZIP，`index.html` 位于根目录（外层多包一层文件夹亦可，自动提升）。
应用内「导入站点 → ?」有图文说明，[Releases](https://github.com/Samge0/PocketSite/releases) 附示例站点包。

**构建**：JDK 17+ 与 Android SDK（compileSdk 35），`./gradlew assembleRelease`。
签名优先级：CI 环境变量（KEYSTORE_BASE64 等）> 本地 `app/pocketsite-release.jks` + `.secrets/`。

## License

[MIT](LICENSE)
