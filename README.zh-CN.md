<div align="center">

# DavSrv

**把安卓手机变成 WebDAV 网络硬盘。**

不用 root，不用云盘，不用注册 —— 只走你自己的 Wi-Fi。

[English](README.md) · [更新日志](CHANGELOG.md) · [许可证](LICENSE)

![Platform](https://img.shields.io/badge/Android-6.0%20%E2%80%93%2017-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7F52FF?logo=kotlin&logoColor=white)
![Ktor](https://img.shields.io/badge/Ktor-2.3.12-087CFA)
![License](https://img.shields.io/badge/License-Apache%202.0-blue)
![Languages](https://img.shields.io/badge/i18n-16%20languages-4B5563)

</div>

---

DavSrv 在安卓 App 里跑一个完整的 WebDAV 服务。启动服务、在电脑上映射地址，手机存储就变成一个普通文件夹：读写、改名、删除、播放媒体、断点续传，都和在本地操作一样。

它是为「扔在架子上长期开着」的手机设计的：服务跑在前台服务里，不持有 CPU 唤醒锁，空闲时几乎不产生唤醒。

## 功能

| 能力 | 说明 |
|---|---|
| **WebDAV 协议** | `OPTIONS` `GET` `HEAD` `PUT` `DELETE` `MKCOL` `PROPFIND` `PROPPATCH` `COPY` `MOVE` `LOCK` `UNLOCK` —— RFC 4918，含排他写锁（有效期上限 2 小时） |
| **两种存储后端** | SAF 目录授权（`DocumentFile`）或本地路径直读（`java.io.File`），运行中随时切换 |
| **认证** | HTTP Basic、多账号、PBKDF2-HMAC-SHA1 加盐哈希、可按账号设为只读、认证结果 10 分钟内存缓存 |
| **传输** | 收发全部流式（64 KiB 缓冲）、`Range` 请求与 `Content-Range` 支持拖动与续传、`ETag` / `Last-Modified` 配合 `If-None-Match` 与 `If-Modified-Since` 做缓存协商 |
| **内置网页版** | 浏览器打开根地址即可浏览、下载、上传、新建文件夹；支持深色模式，配色与 App 一致 |
| **前台服务** | 通知栏直接启停，地址常驻可见，二维码在首页卡片，换网自动刷新 IP，可选开机自启 |
| **省电** | 不持有唤醒锁，请求日志走内存环形缓冲，只在你需要长传输时才可选 Wi-Fi 锁 |
| **16 种语言** | 默认跟随系统语言；阿拉伯语、乌尔都语自动 RTL 布局 |

### 兼容客户端

Windows 资源管理器（映射网络驱动器）· macOS Finder（⌘K）· iOS / iPadOS「文件」（连接服务器）· rclone · RaiDrive · Kodi（WebDAV 源）

## 环境要求

- Android 6.0（API 23）～ Android 17（API 37）
- 手机与客户端在同一 Wi-Fi / 同一网段

## 安装

**直接装 APK** —— 到 [Releases](../../releases) 页下载 `DavSrv-1.0.0-debug.apk` 侧载。debug 包用的是默认调试签名，自己用没问题，但不要拿它对外分发。

**自己编译**

```bash
git clone https://github.com/<your-name>/DavSrv.git
cd DavSrv
./gradlew assembleDebug
```

- 需要 JDK 17、Android SDK（platform 37 + build-tools 36.0.0）
- 产物：`app/build/outputs/apk/debug/app-debug.apk`
- 也可以直接用 Android Studio 打开工程目录（Gradle 8.13 / AGP 8.13 / Kotlin 2.0.20）

仓库里的 Gradle wrapper 指向腾讯镜像，国内拉取较快；**在中国大陆以外使用，请把 `gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 换回 `services.gradle.org`**。

**签名的 release 包**从环境变量读取密钥库，仓库里不放任何密钥：

```bash
export KEYSTORE_PATH=/path/to/release.jks KEYSTORE_PASS=... KEY_ALIAS=... KEY_PASS=...
./gradlew assembleRelease
```

## 快速开始

1. 打开 DavSrv，点「启动服务」。
2. 首页卡片与通知栏会显示 `http://<手机IP>:8080`，二维码扫开就是同一个网页。
3. 在电脑上连接：

   | 客户端 | 操作 |
   |---|---|
   | Windows | 映射网络驱动器 → `http://192.168.1.20:8080` → 勾选「使用其他凭据连接」 |
   | macOS | Finder → ⌘K → `http://192.168.1.20:8080` |
   | iOS / iPadOS | 「文件」→ ⋯ → 连接服务器 → 填同一地址 |
   | rclone | `rclone config` → 新建 → 类型选 `webdav` → 填 URL 与账号 |

4. 用默认账号 **`root` / `1234`** 登录，然后到「账号」页改掉。

> **正式使用前请先改默认密码。** 这是公开的默认凭据，而服务对整个局域网可见。

## 存储模式与「能不能写」

首页「共享目录」卡片右上角会实时显示存储状态：**可读写 / 只读 / 无权限**。只有在「可读写」状态下才能写入，其余情况服务端统一返回 `403` 并在响应正文里说明原因。

- **选择文件夹（SAF）** —— Android 11+ 推荐。通过系统选择器授权一个目录，App 只持有这一条持久化 URI 权限，不需要任何宽泛的存储权限，换机、升级 App 都还在。
- **直接路径** —— 在超大目录下列表更快，但 Android 11+ 需要「所有文件访问」权限。没给这个权限时，部分位置仍能列目录和读取 —— 这正是状态显示「只读」的典型原因。

两种模式都可以用卡片上的**存储自检**按钮验证。它会真实执行一次「新建 → 写入 → 重命名 → 读回 → 删除」，提前告诉你这个目录到底支不支持改名和删除，而不是等客户端弹一个看不懂的错。

## 排查

| 现象 | 原因 | 解决 |
|---|---|---|
| 状态「只读」（路径模式） | 未授予「所有文件访问」 | 点状态标记，在系统设置中允许；或改用「选择文件夹」 |
| 状态「只读」（SAF 模式） | 目录授权失效或被授为只读 | 重新选一次文件夹 |
| 状态「无权限」 | 完全没有存储权限 / 目录已不存在 | 按提示授权或重新选择 |
| 状态正常但电脑端写入失败 | 当前登录的是只读账号 | 到「账号」页确认该账号未勾选只读 |
| 返回 `423 Locked` | 上次会话留下的锁占着文件 | 停止再启动服务即可（锁只存在内存里） |
| 重命名提示「找不到该项目」 | 1.0.0 之前的构建在复制回退失败时仍删了源文件 | 已修复：同目录移动走原子 rename，复制失败绝不删源文件 |
| Windows 无法用 Basic 认证映射 | WebClient 默认禁止明文 HTTP 上的 Basic 认证 | 在 `HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings\DAV` 下把 `BasicAuthLevel` 设为 `2`，然后重启 **WebClient** 服务 |
| 上传后文件名多出 `(1)` 或后缀 | SAF provider 擅自改了文件名 | 已修复：创建后校验文件名，不一致则改回或回滚 |

「日志」页会记录每次请求的方法、路径、账号、状态码与字节数。**状态码是最快的定位手段**：`403` 是权限，`404` / `409` 是路径，`423` 是锁。

## 目录结构

```
app/src/main/kotlin/com/oaurumo/davsrv/
├── DavSrvApp.kt            应用入口：初始化仓库、服务器、通知渠道
├── MainActivity.kt         Compose 宿主、权限与目录选择
├── LocaleHelper.kt         语言解析与 Context 包装
├── data/                   设置、账号、日志、内存仓库（DataStore + StateFlow）
├── server/
│   ├── WebDavServer.kt     Ktor CIO 引擎、路由、认证、请求派发
│   ├── DavHandlers.kt      各 WebDAV 方法实现（流式读写、Range、递归复制）
│   ├── Vfs.kt              DavNode 抽象 + File / SAF 两种实现
│   ├── LockManager.kt      RFC 4918 排他写锁
│   ├── Storage.kt          根目录解析、分版本存储权限、存储自检
│   ├── ServerText.kt       服务端响应文案的本地化
│   ├── BrowserPage.kt      浏览器版文件管理页
│   └── Xml.kt              Multistatus / PROPFIND 序列化
├── service/                前台服务与通知
├── receiver/               开机自启、网络变化
└── ui/                     Compose 界面（概览 / 文件 / 账号 / 日志 / 设置）
```

## 实现要点

- **前台服务类型** —— Android 14+ 使用 `specialUse`（附 subtype 说明），避开 Android 15 起 `dataSync` 每天 6 小时的配额；旧系统回退到 `dataSync`。
- **大文件不落内存** —— 上传边读边写（64 KiB 缓冲），下载以 `OutgoingContent` 流式响应并统计字节数。
- **路径安全** —— 请求路径按段解码，拒绝 `..`、嵌入的分隔符与 NUL 字节，解析结果永远越不出共享根目录。
- **原子性** —— 同后端 `MOVE` 走 `renameTo`；跨后端走「递归复制 + 成功后才删除」。
- **并发** —— 处理器无状态，锁表与字节计数使用并发容器与原子变量。
- **大目录性能** —— 这是多数 WebDAV 服务端的瓶颈所在：
  - SAF 模式改为直接用 `DocumentsContract` 单次投影查询，一个目录下所有子项的名称、大小、时间、MIME、标志位**一次跨进程查询全部取回**（旧的 `DocumentFile` 路径每个属性都要单独查一次，1000 个文件约 5000 次 IPC）；
  - `FileNode` 在构造时快照 `isDirectory`，PROPFIND 不再对同一项重复 `stat`；
  - 父目录解析改惰性求值，只有 `PUT` / `MKCOL` 真正需要时才列父目录；
  - 日期格式化改为每线程一个 `SimpleDateFormat`，不再共享一把同步锁。
  - 桌面基准：3000 项的目录做 PROPFIND（Depth: 1）约 0.8 秒，含 2.2 MB XML 传输。
- **多语言** —— 全部文案在 `res/values*/strings.xml`（203 条 × 16 种语言，脚本逐条校验键集合与占位符一致）。WebDAV 层没有 Activity，`ServerText` 会用当前语言包一层 Context 并按语言标签缓存 —— **切换语言后下一条响应就是新语言，不用重启服务**。印尼语同时提供 `values-in` 与 `values-id`：不同 Android 版本分别上报旧 ISO 码与新码，而 aapt2 会把两者存成两套独立配置，两份都保留才能保证任一情况下都能命中。

## 已知限制

- **仅 HTTP，不含 TLS。** Basic 凭据与文件内容对同网段的嗅探者可见。请只在可信网络使用，公网访问请自行套一层 HTTPS 反向代理。
- **自定义死属性不持久化。** `PROPPATCH` 返回成功但不保存，常见客户端不依赖此特性。
- **Android 10 对整盘路径访问有限制**，建议改用 SAF 目录授权。
- **只提供简体中文一套资源**（`values-zh-rCN`）：系统语言为繁体中文（zh-TW / zh-HK）时显示简体，而不是回退英语。
- **一个服务实例对应一个目录树**：共享根是单个文件夹，默认不是整个文件系统。

## 参与

欢迎提 issue 与 PR。报 bug 时请附上 Android 版本、使用的客户端，以及「日志」页里那条请求的状态码 —— 这三样基本就能定位问题。

## 许可证

[Apache-2.0](LICENSE)。

## 免责声明

DavSrv 会把你的存储暴露到局域网。账号、共享目录、所在的网络都由你负责。**不要把端口直接暴露到公网**；确需远程访问，请在前面加上 TLS 与反向代理。
