# PocketSite

<p align="center">
  <b>手机端的静态站点管理器</b><br>
  多站点 · 独立端口 · 一键启停 · 完全离线
</p>

PocketSite 在手机上运行一个嵌入式 HTTP 服务器，把多个静态站点装进口袋：每个站点独立端口、独立开关，可同时运行；通过应用内 WebView 预览或系统浏览器访问，全程无需网络，也不依赖 Termux 等外部环境。

## 功能特性

- **多站点并行**：每个站点一个独立 HTTP 服务器实例（独立端口），同时运行互不干扰；端口随机分配并做冲突检测（查重 + 设备占用探测），也可手动修改
- **一键启停**：前台 Service 保活，站点卡片开关直接控制；启动过程有加载动效与就绪提示
- **多种部署方式**：本地 ZIP 导入 / URL 在线拉取（带下载进度），内容更新保留端口与设置
- **应用内预览**：WebView 直接访问回环地址，断网环境同样可用（部分浏览器断网时会拦截 127.0.0.1，应用内预览不受影响）
- **站点包格式自检**：自动剥离 ZIP 外层目录、忽略 `__MACOSX` 垃圾文件、缺失 `index.html` 标记警示、防路径穿越
- **站点根目录可迁移**：应用私有目录（默认，免权限）与设备任意目录之间自由切换；迁移采用「复制 → 字节级校验 → 原子替换」流程，失败自动保留原数据
- **随应用启动**：为站点开启后，每次打开 App 自动拉起对应服务器

## 站点包格式

一个标准的 ZIP 压缩包，入口 `index.html` 位于根目录：

```
site.zip
├── index.html        ← 必需，站点入口
├── css/
├── js/
└── images/
```

也支持 ZIP 内只有一个文件夹且其中含 `index.html` 的结构（自动提升为站点根目录）。应用内「导入站点 → ?」有完整的图文说明。

## 使用

1. 安装 APK，首次启动授予通知权限
2. 「导入 ZIP」选择打包好的站点包，或「URL 拉取」在线下载
3. 打开站点卡片上的开关启动服务器
4. 点 🌐 应用内预览，或 ↗ 用系统浏览器访问 `http://127.0.0.1:<端口>/`

同一局域网内的电脑也可通过手机 IP 直接访问（服务器绑定 `0.0.0.0`）。

## 构建

要求：JDK 17+、Android SDK（compileSdk 35）。

```bash
git clone https://github.com/Samge0/PocketSite.git
cd PocketSite
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 技术栈

| 项 | 说明 |
|---|---|
| 语言 / UI | Kotlin 2.0.21 · Jetpack Compose（Material 3） |
| 构建 | Gradle 8.13 · AGP 8.7.3 · minSdk 26 / targetSdk 35 |
| 运行时依赖 | **零**——HTTP 服务器、ZIP 解压、下载器均为纯 Kotlin 标准库实现 |

## 项目结构

```
app/src/main/java/com/samge/pocketsite/
├── MainActivity.kt        # 导航宿主 + 主页/详情页/设置页 UI + ViewModel
├── StaticSiteServer.kt    # 嵌入式 HTTP 服务器（SPA fallback / Range 206 / 防路径穿越）
├── SiteServerService.kt   # 前台 Service，按站点管理多个服务器实例
├── SitesRepository.kt     # 站点仓库：导入/更新/删除/端口分配/迁移
├── Migrator.kt            # 目录迁移：复制→校验→替换，失败可回滚
├── SitePreviewActivity.kt # 应用内预览（WebView + 工具栏）
└── Theme.kt               # Material 3 深色主题
```

## License

[MIT](LICENSE)
