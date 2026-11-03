# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] — 2026-11-03

First public release.

### Added

- Full WebDAV implementation on the Ktor CIO engine: `OPTIONS`, `GET`, `HEAD`, `PUT`, `DELETE`, `MKCOL`, `PROPFIND`, `PROPPATCH`, `COPY`, `MOVE`, `LOCK`, `UNLOCK`.
- Two storage backends behind a single `DavNode` interface: SAF folder grants (`DocumentFile` / `DocumentsContract`) and direct path access (`java.io.File`), switchable at runtime.
- HTTP Basic authentication with multiple accounts, PBKDF2-HMAC-SHA1 salted hashes, per-account read-only flag, and a 10-minute in-memory auth cache.
- Streaming uploads and downloads, `Range` / `If-Range` support, strong `ETag` and `Last-Modified` headers.
- Built-in browser file manager: browse, download, upload, create folder, dark mode, RTL aware.
- Foreground service with start/stop action, address and QR code in the notification, boot autostart, IP refresh on network change.
- Storage status badge (read-write / read-only / no access) and a self-test that performs a real create → write → rename → read-back → delete cycle.
- 16 UI languages: English, Simplified Chinese, Hindi, Bengali, Urdu, Arabic, Spanish, Portuguese, Russian, German, French, Japanese, Korean, Indonesian, Malay, Thai. Language follows the system locale by default, with an explicit picker in Settings.
- Localized server responses: WebDAV error bodies (`srv_*`) and the built-in browser page (`web_*`) follow the selected language.

### Fixed

- **Write operations appeared to fail on desktop clients.** `PUT`, `DELETE`, `MKCOL`, `COPY`, `MOVE` and `UNLOCK` executed the operation but never called `call.respond()`, so Ktor appended a default `404`. Files were actually written, renamed, or deleted, while Windows Explorer reported failures — including the misleading *"The item could not be found"* when renaming. All handlers now respond explicitly, and the full Windows request sequence (Chinese directory names, spaces, lock tokens, rename, copy, delete) is covered by a desktop JVM regression harness.
- **Data loss when a cross-backend move failed.** A `Result`-typed copy helper returned a boolean, so the failure check never fired and the source was deleted even when the copy had failed. Moves within one backend now use an atomic rename, and a failed copy never deletes the source.
- **SAF providers silently renaming files.** A created file whose name the provider altered is now verified against the requested name, renamed back, or rolled back.
- **`UNLOCK` rejected by the lock pre-check** on resources held by the lock being released.
- **Self-test false failure.** The read-back was compared by a hard-coded length instead of by content; it now uses `contentEquals` over a 16-byte payload.
- **Slow directory listing with many files.** SAF mode previously queried each attribute separately (about 5000 IPC calls for 1000 entries). It now issues a single projection query per directory; path mode avoids re-`stat`-ing entries; parent resolution is lazy; date formatters are per-thread. A 3000-entry directory now lists in about 0.8 s.

### Changed

- **Default account is now `root` / `1234`** (previously `admin` / `admin`). Existing installations keep their stored credentials — change the password on the Accounts tab.
- Storage card buttons are stacked full-width instead of sharing one row.
- Lock lifetime is capped at 2 hours so a crashed client cannot hold a file forever.

[1.0.0]: https://github.com/<your-name>/DavSrv/releases/tag/v1.0.0
