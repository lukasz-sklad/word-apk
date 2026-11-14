# Security policy (`lukasz-sklad/word-apk`)

## Reporting a vulnerability

Please report security problems in this Android fork privately through GitHub:
**[Report a vulnerability](https://github.com/lukasz-sklad/word-apk/security/advisories/new)**
(Security tab › Advisories › Report a vulnerability).

Do not open a public issue or pull request for an unpatched vulnerability.

If the vulnerability affects the shared document parsing or rendering core (`crates/doc`, `crates/docx`, `crates/formats`, `crates/layout`, `crates/pdf`), we will coordinate or forward details to upstream [`storytold/wordcraft`](https://github.com/storytold/wordcraft) as appropriate.

Useful to include:
* APK version (`versionName` / `versionCode` or commit SHA) and Android device / OS version (`arm64-v8a` or `x86_64`);
* steps or a minimal sample file (`.docx`, `.doc`, `.odt`, `.rtf`, `.html`, `.md`, `.txt`) reproducing the issue;
* what happens (crash/panic, unexpected intent/file access, corrupted state).

## Supported versions

Fixes go into `main` (`lukasz-sklad/word-apk`) and ship in the next `0.<upstream>.<apk>` release. Older APK builds do not receive backports.

## Scope & threat model (Android APK)

* **Offline by design:** The APK declares **no `INTERNET` permission** in [`AndroidManifest.xml`](android/app/src/main/AndroidManifest.xml) and compiles without `craft-update` or `wordcraft-mcp` (there is no network updater, remote control socket, or MCP server on Android).
* **In scope:** Hostile document inputs causing crashes/panics, out-of-bounds reads, excessive memory allocation, JNI bridge faults, or unsafe `content://` / `file://` URI handling via Android Storage Access Framework (SAF) / View Intents.
* Every panic from hostile input is treated as a bug.
