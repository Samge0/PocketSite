# PocketSite — 手机静态站点演示器

面试离线作品展示：多个静态站点装进手机，每站独立端口、独立开关，一键启停内嵌 HTTP 服务器。纯 App 实现，无需 Termux。

**设计**：Material 3 Expressive 深色（deep-navy + mint 强调）——呼吸状态灯、渐变按钮、字母头像瓦片、全中文界面。

## 核心概念

- **站点 = 根目录下子目录 + 独立端口**（prefs 持久化，随机分配、冲突检测：查已用端口 + 设备占用探测）
- **多站并行**：每个站点独立 ServerSocket 实例，可同时运行，互不影响
- **无内置站点**：所有站点通过 本地 ZIP 导入 或 URL 拉取 进入（MVP 定位，包不进 APK）

## 主页

- 站点 item：字母头像、站点名、运行状态点 + 端口、启动开关、三个图标键（WebView 预览 / 系统浏览器 / 复制链接）
- 点 item 进详情页；FAB ＝ 导入（弹层选 ZIP / URL）
- 空态：引导卡片「导入第一个站点」
- 顶栏 ⚙ 进设置

## 站点详情页

- 启动开关 + 实时 URL；运行中显示 预览/浏览器/复制 三键
- 设置：随应用启动（打开 App 自动拉起该站）、端口修改（需先停止）、站点包管理（更新包=覆盖内容保留端口与设置 / 删除站点=连端口设置一起清）

## 设置页

- 站点根目录：应用私有（默认，免权限）↔ 任意目录（SAF + 所有文件访问授权）
- 迁移：复制 → 字节校验 → 原子替换 → 才删旧目录；失败保留原数据；有站点运行时禁迁

## 服务器（StaticSiteServer，纯 Kotlin 零依赖）

SPA fallback、HTTP Range 206（视频拖动）、路径穿越防护、30+ MIME、每站点一个端口一个实例。

## 构建

```
export JAVA_HOME="F:/APP/Android/Android Studio/jbr"
cd F:/Space/PRO/android/PocketSite
"F:/APP/Android/Android Studio/jbr/bin/java.exe" -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain assembleDebug --no-daemon
```

产物 ≈14MB（不含站点包，站点全靠导入）。

## 离线演示说明

服务器绑回环 + 全接口，飞行模式下照常工作（实测 Active default network: none 时仍 200）。但多数国产浏览器断网时会拦截 127.0.0.1（联网预检直接弹无网页）——**离线演示用 item 上的 WebView 预览**，无预检必开。

## 验证（2026-10-17，Small_Phone AVD）

- 空态引导卡 → FAB/引导按钮 → 底部弹层（ZIP/URL 双选项）
- SAF 选 ZIP 导入 109MB 主站点包 → 自动随机端口 ：8536
- URL 拉取（宿主机 http.server → 10.0.2.2）→ site-two 独立端口 ：9207，与 ：8536 不冲突
- 双站并行：site-two :9207 返回内容同时 import :8536 保持 200 + Range 206 + SPA fallback
- 详情页：启动开关、随应用启动、端口修改、更新包、删除（确认弹窗）
- 历史验证延续：离线 WebView 预览、迁移算法、路径穿越防护
