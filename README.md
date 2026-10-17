<div align="center">

# PocketSite

**手机端的静态站点管理器**

多站点 · 独立端口 · 一键启停 · 完全离线

[![Release](https://img.shields.io/github/v/release/Samge0/PocketSite?style=flat-square)](https://github.com/Samge0/PocketSite/releases)
[![Min SDK](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-MIT-5EEAD4?style=flat-square)](LICENSE)

[English](README.en.md) | 中文

</div>

---

PocketSite 是一个手机端的静态站点管理器：内嵌 HTTP 服务器，把多个静态站点装进口袋。
每个站点独立端口、独立开关、可同时运行；应用内 WebView 预览断网可用，全程无需 Termux 或 root。

## 为什么是 PocketSite

Android 上的"本地服务器"应用大多是 shell 环境的包装——安装重、操作绕。PocketSite 是一个纯原生应用：
选一个 ZIP，拨一个开关，站点就跑在 `http://127.0.0.1:<端口>`。它为"身边没有网络、但需要展示一个真实可访问的站点"的时刻而生。

- **无 Termux、无 root、无网络依赖** —— 一个 APK，全部内置
- **多站点并行** —— 每个站点是独立端口上的隔离服务器实例，想跑几个跑几个
- **飞行模式照常工作** —— 回环接口永不掉线

## 功能

| | |
|---|---|
| 🚀 **一键启停** | 前台服务保活站点；呼吸状态灯一眼看清服务器状态 |
| 🗂 **站点库** | 字母头像卡片，实时端口/运行状态/快捷操作：应用内预览 · 系统浏览器 · 复制链接 |
| 📦 **部署方式多样** | 本地 ZIP 导入或 URL 在线拉取（带下载进度）；内置图文打包指南 |
| 🔀 **内容热更新** | 「更新包」原地覆盖内容——端口与设置原样保留 |
| 🛡 **加固的服务器** | 纯 Kotlin 手写 HTTP/1.1：SPA fallback、HTTP Range 流式（视频拖动）、路径穿越防护、30+ 类型 MIME |
| 📁 **存储位置可迁移** | 站点根目录可在应用私有存储与设备任意目录间切换；迁移流程 = 复制 → 字节校验 → 原子替换，失败自动回滚 |
| ⚡ **随应用启动** | 标记站点后，每次打开 App 自动拉起对应服务器 |
| 🔒 **安全外跳** | 外部链接跳转前确认（可在设置中关闭提醒） |

## 站点包格式

标准 ZIP 压缩包，`index.html` 位于根目录：

```
site.zip
├── index.html        ← 必需，站点入口
├── css/ …
├── js/ …
└── images/ …
```

ZIP 内只有一个文件夹且其中含 `index.html` 的结构也可以——会自动提升为站点根目录。
应用内有完整图文指南（**导入站点 → ?**），每个 [Release](https://github.com/Samge0/PocketSite/releases)
都附带示例站点包（`sample-site.zip` / `samgeai-static-site.zip`）。

## 快速开始

1. 从 [Releases](https://github.com/Samge0/PocketSite/releases) 下载最新 APK 并安装
2. 导入站点：**＋ → 从本地 ZIP 导入**（或 URL 拉取）
3. 拨动站点卡片上的开关——服务器即刻运行在分配的端口上
4. 点 🌐 应用内预览，或 ↗ 用系统浏览器访问 `http://127.0.0.1:<端口>/`

> **离线提示**：无网络环境请使用应用内 🌐 预览。部分手机浏览器有联网预检，飞行模式下会
> 拒绝加载 `127.0.0.1`；应用内 WebView 直连服务器，不受影响。

同一 Wi-Fi 下的电脑也可以通过 `http://<手机IP>:<端口>/` 直接访问你的站点——大屏演示很方便。

## 构建

要求：JDK 17+、Android SDK（compileSdk 35）。

```bash
git clone https://github.com/Samge0/PocketSite.git
cd PocketSite
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleRelease      # 签名 release（见下方签名说明）
./gradlew assembleDebug        # 或 debug 构建
```

### 签名

Release 通过 CI secrets 签名（`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`）。
本地构建时，把 keystore 放到 `app/pocketsite-release.jks`，凭据写入 `.secrets/*.txt`——
构建会自动选择可用的签名源（CI 环境变量优先）。两种来源都没有时回退 debug 签名。

## 技术

| | |
|---|---|
| 语言 / UI | Kotlin 2.0.21 · Jetpack Compose（Material 3） |
| 构建 | Gradle 8.13 · AGP 8.7.3 · minSdk 26 · targetSdk 35 |
| 运行时依赖 | **无** —— HTTP 服务器、ZIP 引擎、下载器全部基于 Kotlin 标准库手写 |

## 项目结构

```
app/src/main/java/com/samge/pocketsite/
├── MainActivity.kt        # 导航宿主 + 主页/详情页/设置页
├── StaticSiteServer.kt    # 嵌入式 HTTP 服务器（SPA fallback / Range / 防路径穿越）
├── SiteServerService.kt   # 前台服务，按站点管理多个服务器实例
├── SitesRepository.kt     # 站点仓库：导入/更新/删除/端口分配
├── Migrator.kt            # 存储迁移（复制 → 校验 → 原子替换，可回滚）
├── SitePreviewActivity.kt # 应用内预览（WebView + 工具栏 + 外链确认）
├── GithubMark.kt          # GitHub 官方矢量图标
└── Theme.kt               # Material 3 深色主题
```

## FAQ

<details>
<summary><b>为什么断网后浏览器打不开 127.0.0.1？</b></summary>

服务器没问题——回环接口永不掉线。很多厂商浏览器（OPPO、vivo、小米、华为…）会做联网预检，
在请求到达 `127.0.0.1` 之前就弹出自己的"无网络"页。请改用应用内 🌐 预览，它直连服务器，无预检。
</details>

<details>
<summary><b>端口会冲突吗？</b></summary>

不会。每个站点分配 8000–9999 内的随机端口，分配时会查重其他站点已用端口并探测设备占用。
站点详情页可以修改端口（需先停止站点）。
</details>

<details>
<summary><b>站点存在哪里？</b></summary>

默认在应用私有存储（App 升级不丢，卸载会清）。在 **设置 → 站点根目录** 切换到用户目录
（需授予「所有文件访问」权限）即可跨重装保留站点——迁移自动完成且可回滚。
</details>

## License

[MIT](LICENSE)
