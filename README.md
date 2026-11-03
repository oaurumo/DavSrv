<div align="center">

# DavSrv

**Turn an Android phone into a WebDAV network drive.**

No root, no cloud, no account — just your own Wi-Fi.

[简体中文](README.zh-CN.md) · [Changelog](CHANGELOG.md) · [License](LICENSE)

![Platform](https://img.shields.io/badge/Android-6.0%20%E2%80%93%2017-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7F52FF?logo=kotlin&logoColor=white)
![Ktor](https://img.shields.io/badge/Ktor-2.3.12-087CFA)
![License](https://img.shields.io/badge/License-Apache%202.0-blue)
![Languages](https://img.shields.io/badge/i18n-16%20languages-4B5563)

</div>

---

DavSrv runs a full WebDAV server inside an Android app. Start the service, map the address on your computer, and the phone's storage shows up as a normal folder in Windows Explorer or Finder — read, write, rename, delete, stream media, resume interrupted downloads.

It is built for phones that stay on a shelf: the server runs in a foreground service, holds no CPU wake lock, and does nothing at all while idle.

## Features

| Capability | Details |
|---|---|
| **WebDAV** | `OPTIONS` `GET` `HEAD` `PUT` `DELETE` `MKCOL` `PROPFIND` `PROPPATCH` `COPY` `MOVE` `LOCK` `UNLOCK` — RFC 4918, including exclusive write locks with a 2-hour TTL cap |
| **Two storage backends** | SAF folder grant (`DocumentFile`) or direct path access (`java.io.File`), switchable at runtime |
| **Authentication** | HTTP Basic, multiple accounts, PBKDF2-HMAC-SHA1 salted hashes, per-account read-only flag, 10-minute in-memory auth cache |
| **Transfers** | Fully streaming in both directions (64 KiB buffer), `Range` requests with `Content-Range` for seeking and resume, `ETag` / `Last-Modified` with `If-None-Match` and `If-Modified-Since` |
| **Built-in web UI** | Open the root URL in a browser to browse, download, upload, and create folders; dark mode, same palette as the app |
| **Foreground service** | Start / stop from the notification, address always visible there, QR code on the home screen, IP refreshed on network change, optional boot autostart |
| **LAN domain (mDNS)** | Advertises a fixed `.local` name — two random letters by default (e.g. `qx.local`), custom prefix and optional NIC binding — plus a `_webdav._tcp` Bonjour service; toggleable on the home screen |
| **Power friendly** | No wake locks, logs held in an in-memory ring buffer, optional Wi-Fi lock only while you want stable large transfers |
| **16 languages** | Follows the system locale by default; Arabic and Urdu get RTL layouts |

### Compatible clients

Windows File Explorer (mapped network drive) · macOS Finder (⌘K) · iOS / iPadOS Files ("Connect to Server") · rclone · RaiDrive · Kodi (WebDAV source)

## Requirements

- Android 6.0 (API 23) through Android 17 (API 37)
- Wi-Fi network shared with the client device (same subnet)

## Install

**From a build** — grab `DavSrv-1.0.1-debug.apk` from the [Releases](../../releases) page and sideload it. Debug builds are signed with the default debug key, which is fine for personal use but not for distribution.

**From source**

```bash
git clone https://github.com/<your-name>/DavSrv.git
cd DavSrv
./gradlew assembleDebug
```

- JDK 17, Android SDK with platform 37 and build-tools 36.0.0
- Output: `app/build/outputs/apk/debug/app-debug.apk`
- Or just open the folder in Android Studio (Gradle 8.13 / AGP 8.13 / Kotlin 2.0.20)

The checked-in Gradle wrapper points at a Tencent mirror for speed inside mainland China. Outside China, change `distributionUrl` in `gradle/wrapper/gradle-wrapper.properties` back to `services.gradle.org`.

**Signed release builds** read the keystore from environment variables, so no secrets live in the repo:

```bash
export KEYSTORE_PATH=/path/to/release.jks KEYSTORE_PASS=... KEY_ALIAS=... KEY_PASS=...
./gradlew assembleRelease
```

## Quick start

1. Open DavSrv and tap **Start**.
2. The home card and the notification show `http://<phone-ip>:8080` — the QR code opens the same page.
3. Connect from your computer:

   | Client | Steps |
   |---|---|
   | Windows | *Map network drive* → `http://192.168.1.20:8080` → tick *Connect using different credentials* |
   | macOS | Finder → ⌘K → `http://192.168.1.20:8080` |
   | iOS / iPadOS | Files → ⋯ → *Connect to Server* → same URL |
   | rclone | `rclone config` → new remote → `webdav` → URL + account |

4. Sign in with the default account **`root` / `1234`**, then change it on the **Accounts** tab.

On Windows 10+, macOS and iOS the phone is also reachable as `http://<name>.local:8080` — the exact URL is shown on the home card. On the home screen (the LAN-domain card above the shared-folder card) you can set a custom prefix and pin the announcement to a specific network interface (auto by default). Changes apply while the server is running, and the IP URL always works as a fallback.

> **Change the default password before your first real use.** It is a well-known credential, and the server is reachable by anything on the same network.

## Storage: which mode, and is it writable?

The home screen's storage card shows a live badge: **Read-write**, **Read-only**, or **No access**. Writes are only possible in the first state — everything else returns `403` with an explanatory body.

- **Folder picker (SAF)** — recommended on Android 11+. You grant one folder through the system picker; the app holds a persistable URI permission and needs no broad storage permission. Portable across devices and survives app updates.
- **Direct path** — faster on large directories, but on Android 11+ it requires *All files access*. Without that grant the app can still list and read some locations, which is exactly why the badge can say *Read-only*.

Both modes are backed by the **self-test** button on the storage card. It really creates a file, writes to it, renames it, reads it back, and deletes it — telling you up front whether the chosen folder supports rename and delete, rather than letting you find out through a cryptic client error.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Badge says **Read-only** (path mode) | *All files access* not granted | Tap the badge and allow it in system settings, or switch to the folder picker |
| Badge says **Read-only** (SAF mode) | Folder grant was revoked, or granted read-only | Pick the folder again |
| Badge says **No access** | No storage permission / folder is gone | Follow the prompt, re-grant |
| Badge is fine but writes fail from the PC | You are signed in with a read-only account | Check the account on the **Accounts** tab |
| `423 Locked` | A stale lock from a previous session holds the file | Stop and start the service (locks are in memory only) |
| *"The item could not be found"* when renaming | Pre-1.0.0 builds deleted the source when the copy fallback failed | Fixed — same-folder moves use an atomic rename, and a failed copy never deletes the source |
| Windows cannot map the drive with Basic auth | WebClient blocks Basic over plain HTTP by default | Set `BasicAuthLevel = 2` under `HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings\DAV`, then restart the **WebClient** service |
| `<name>.local` does not resolve | Multicast blocked (AP isolation), or the OS has no mDNS resolver — Linux needs Avahi; Android browsers may not resolve before Android 12 | Use the IP URL, disable AP isolation, or install an mDNS resolver |
| Start fails (or crashes on old versions) with a port below 1024 | Android apps are unprivileged users; the Linux kernel forbids claiming ports below 1024 | The port setting accepts 1024 - 65535 |
| Uploaded file gets a `(1)` suffix | The SAF provider renamed the file it was given | Fixed — the created name is verified against the requested one and rolled back if it differs |

The **Logs** tab records method, path, account, status code and byte count for every request — that status code is the fastest way to tell a permission problem (`403`) from a path problem (`404` / `409`) or a lock (`423`).

## Project layout

```
app/src/main/kotlin/com/oaurumo/davsrv/
├── DavSrvApp.kt            Entry point: repo, server, notification channel
├── MainActivity.kt         Compose host, permissions, folder picker
├── LocaleHelper.kt         Locale resolution and context wrapping
├── data/                   Settings, accounts, logs, in-memory repo (DataStore + StateFlow)
├── server/
│   ├── WebDavServer.kt     Ktor CIO engine, routing, auth, dispatch
│   ├── DavHandlers.kt      WebDAV method implementations (streaming, Range, recursive copy)
│   ├── Vfs.kt              DavNode abstraction + File / SAF implementations
│   ├── LockManager.kt      RFC 4918 exclusive write locks
│   ├── Storage.kt          Root resolution, version-specific permissions, self-test
│   ├── ServerText.kt       Localized bodies for server responses
│   ├── BrowserPage.kt      Browser file manager page
│   └── Xml.kt              Multistatus / PROPFIND serialization
├── service/                Foreground service and notifications
├── receiver/               Boot and network-change receivers
└── ui/                     Compose screens (Overview / Files / Accounts / Logs / Settings)
```

## Implementation notes

- **Foreground service type** — Android 14+ uses `specialUse` with a documented subtype, avoiding the 6-hour daily quota that Android 15 puts on `dataSync`; older releases fall back to `dataSync`.
- **Nothing is buffered whole** — uploads stream to disk through a 64 KiB buffer, downloads are returned as `OutgoingContent` with byte counting.
- **Path safety** — request paths are decoded per segment; `..`, embedded separators and NUL bytes are rejected, and resolution can never escape the shared root.
- **Atomicity** — same-backend `MOVE` uses `renameTo`; cross-backend moves copy recursively and delete only after a successful copy.
- **Concurrency** — handlers are stateless; the lock table and byte counters use concurrent collections and atomics.
- **Large directories** — this is where naive WebDAV servers fall over:
  - SAF mode issues one `DocumentsContract` projection query per directory, fetching name, size, mtime, MIME type and flags for all children in a single IPC round trip (the old `DocumentFile` path needed one query per attribute — roughly 5000 IPCs for 1000 files).
  - `FileNode` snapshots `isDirectory` at construction, so PROPFIND no longer re-stats the same entry.
  - Parent resolution is lazy: only `PUT` and `MKCOL` actually need to list the parent.
  - Date formatting uses one `SimpleDateFormat` per thread instead of a shared synchronized instance.
  - Desktop benchmark: PROPFIND (Depth: 1) over a 3000-entry directory in about 0.8 s, 2.2 MB of XML included.
- **Localization** — all UI text lives in `res/values*/strings.xml` (203 strings × 16 languages, verified by script for identical key sets and placeholders). The WebDAV layer has no Activity, so `ServerText` wraps the application context with the selected locale and caches it per language tag — switching the language changes the next response, no server restart needed. Indonesian ships as both `values-in` and `values-id` because different Android versions report either the legacy or the current ISO code, and aapt2 stores them as separate configurations.

## Known limitations

- **HTTP only, no TLS.** Basic credentials and file contents are visible to anyone who can sniff the Wi-Fi. Use it on a trusted network, or put a reverse proxy with HTTPS in front.
- **Dead properties are not persisted.** `PROPPATCH` returns success but stores nothing; the usual clients do not depend on it.
- **Android 10 restricts whole-partition paths** — use the folder picker there instead of path mode.
- **Simplified Chinese only** (`values-zh-rCN`): a Traditional Chinese system (zh-TW / zh-HK) shows Simplified rather than falling back to English.
- **One directory tree per server instance** — the shared root is a single folder, not the whole filesystem by default.
- **mDNS depends on multicast.** Routers with AP/client isolation block it; the `.local` name then simply never resolves, and the IP URL keeps working.

## Contributing

Issues and pull requests are welcome. When reporting a bug, please include the Android version, the client you used, and the status code from the **Logs** tab — that combination usually identifies the problem immediately.

## License

[Apache-2.0](LICENSE).

## Disclaimer

DavSrv exposes your storage to the local network. You are responsible for the accounts you create, the folder you share, and the network you run it on. Do not expose the port to the public internet without TLS and a proxy in front of it.
