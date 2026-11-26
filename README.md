# ScanVault for Android

Native Android client (Kotlin + Jetpack Compose) for a self-hosted
[ScanVault](https://github.com/ryanbyte1701/scanvault) server, the vault for local copies of
imaging files and reports from imaging centers.

## What it does

- Sign in to your ScanVault server (`https://…`, or `http://` on a trusted LAN/VPN).
- Unlock the vault with its passphrase or recovery key when the server has restarted.
- Browse and add patients; browse and add studies for each patient.
- Upload to a study from the **camera** (report pages, CD covers) or the **file picker**
  (images, PDFs, DICOM, anything else). Each file is tagged `image`, `report`, `dicom` or `other`.
- Import DICOM files or a zipped imaging-CD export into a patient; the server groups them into studies.
- View images full-screen with pinch-zoom; open PDFs and other files in another app.
- Delete patients, studies and files (long-press a file).

Encryption and the Google Drive / Box / SFTP backups all happen on the server. The phone never
stores the vault passphrase or recovery key.

## Privacy on the device

- The session token is encrypted with an AES-256-GCM key held in the Android Keystore.
- Thumbnails are cached in memory only, never on disk.
- Decrypted copies opened in other apps, and camera captures awaiting upload, live in the app's
  cache and are deleted on every launch and on sign-out.
- App data is excluded from Android cloud backup and device-to-device transfer.
- User-installed CA certificates are trusted, so a server with a private/home CA works once its
  CA is installed on the phone.

## Server API

The app expects these endpoints (all JSON, `Authorization: Bearer <token>`, errors as `{"error": "…"}`):

| Method | Path | Notes |
| --- | --- | --- |
| POST | `/api/auth/login` | `{username, password}` → `{token, expires_at}` |
| POST | `/api/auth/logout` | revokes the token |
| GET | `/api/status` | `{setup_complete, unlocked, authenticated, username, …}` |
| POST | `/api/vault/unlock` | `{passphrase}` or `{recovery_key}` |
| GET/POST | `/api/patients` | list / create |
| GET/DELETE | `/api/patients/{id}` | |
| POST | `/api/patients/{id}/import` | multipart `file` (repeatable): DICOM or .zip |
| GET/POST | `/api/patients/{id}/studies` | list / create |
| GET/DELETE | `/api/studies/{id}` | |
| GET/POST | `/api/studies/{id}/files` | list / multipart upload (`file`, optional `kind`) |
| DELETE | `/api/files/{id}` | |
| GET | `/api/files/{id}/content` | decrypted bytes |
| GET | `/api/files/{id}/thumbnail` | JPEG, when `has_thumbnail` |

A locked vault answers data endpoints with HTTP 423 `{"code": "locked"}`; the app then shows the unlock dialog.

## Building

Requires JDK 17 and the Android SDK (API 35).

```sh
./gradlew testDebugUnitTest assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Every push and pull request is built by GitHub Actions, and the debug APK is attached to the run
as the `scanvault-debug-apk` artifact.
