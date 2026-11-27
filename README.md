# ScanVault for Android

Native Android client (Kotlin + Jetpack Compose) for a self-hosted
[ScanVault](https://github.com/ryanbyte1701/scanvault) server, the vault for local copies of
imaging files and reports from imaging centers.

## Screenshots

The real app in the default dark theme (Teal night), showing a fictional sample vault: no real
patient data. They are rendered by the screenshot tests described under [Building](#building).

<table>
<tr>
<td width="33%"><img src="docs/screenshots/patients.png" alt="Patients list"></td>
<td width="33%"><img src="docs/screenshots/patient.png" alt="A patient's studies"></td>
<td width="33%"><img src="docs/screenshots/study.png" alt="A CT study with report and DICOM thumbnails"></td>
</tr>
<tr>
<td><sub><b>Patients.</b> Everyone in the vault, with search.</sub></td>
<td><sub><b>Patient.</b> Studies, newest first.</sub></td>
<td><sub><b>Study.</b> Reports and images, plus camera and file upload.</sub></td>
</tr>
<tr>
<td><img src="docs/screenshots/viewer.png" alt="DICOM viewer showing a chest CT at level -600, window 1500"></td>
<td><img src="docs/screenshots/settings.png" alt="Settings with theme and dark color presets"></td>
<td><img src="docs/screenshots/sign-in.png" alt="Sign-in screen"></td>
</tr>
<tr>
<td><sub><b>DICOM viewer.</b> Server-side window/level in HU, CT presets.</sub></td>
<td><sub><b>Settings.</b> Same theme and dark colors as the web app.</sub></td>
<td><sub><b>Sign in.</b> Password or single sign-on.</sub></td>
</tr>
</table>

## What it does

- Sign in to your ScanVault server (`https://…`; `http://` only on your own LAN, if you allow it) with a password
  or with any single sign-on provider the server offers ("Continue with Google", Authentik, etc.).
  SSO runs in a Custom Tab with PKCE; the server brokers it, so the app needs no IdP registration.
- Unlock the vault with its passphrase or recovery key when the server has restarted.
- Browse and add patients; browse and add studies for each patient.
- Upload to a study from the **camera** (report pages, CD covers) or the **file picker**
  (images, PDFs, DICOM, anything else). Each file is tagged `image`, `report`, `dicom` or `other`.
- Import DICOM files or a zipped imaging-CD export into a patient; the server groups them into studies.
- View images full-screen with pinch-zoom; open PDFs and other files in another app.
- Compare two studies of a patient side by side (stacked when the phone is upright): pick from
  suggested matches (same kind of scan and body part, closest in time), then zoom, pan,
  window/level and slices stay linked. Unlink to line up anatomy; relinking keeps the offset.
- Delete patients, studies and files (long-press a file).

Encryption and the Google Drive / Box / SFTP backups all happen on the server. The phone never
stores the vault passphrase or recovery key.

## Privacy on the device

- **App lock**: the app locks when it goes to the background (after 1 minute by default; choose
  immediately, 1, 5 or 15 minutes in Settings) and opens again with your fingerprint, face or phone
  PIN. While locked, no screen or dialog is shown at all. Opening the camera, a file picker or a
  report in another app allows that one trip up to 10 minutes. The app warns when the phone has
  no screen lock, and then cannot lock itself. Screenshots, screen recording and the
  recent-apps preview are blocked.
- **Settings and sign-in** (server address, username, session token, appearance): every value is
  AES-256-GCM encrypted with a Keystore key before it reaches SharedPreferences, and each value is
  bound to its name so values cannot be swapped.
- **Files** (reports opened in other apps, camera photos waiting to upload): stored in a chunked
  AES-256-GCM format with a random data key per file. Data keys are generated in software and
  wrapped by a Keystore key (StrongBox when the phone has one); on Android 9+ the Keystore keys can
  only be used while the phone is unlocked. Every 64 KB chunk is authenticated and the header is
  bound to every chunk, so tampering, reordering and truncation are detected.
- **Opening a report in another app** never writes a decrypted copy: the other app gets a file
  descriptor whose reads are decrypted on the fly. Shared copies are deleted after an hour, on
  sign-out, and on the next launch.
- **Camera photos**: the system camera app can only write a plain file, so the photo is plain on
  the phone while the camera is open. The app encrypts it the moment the camera returns, deletes the
  plain copy and uploads from the encrypted one. If the upload fails the photo stays, encrypted, in a
  "waiting to upload" list on the study so you can retry or discard it.
- Thumbnails and rendered DICOM frames are kept in memory only. App data is excluded from cloud
  backup and device transfer. The vault passphrase and recovery key are never stored on the phone.

## Network security

- Servers must use **https**. A plain `http://` address works only if you allow http for that
  one server, and only when it is reached directly (no proxy) at a private address (192.168.x,
  10.x, 172.16–31.x, 100.64/10 VPN ranges, or LAN host names resolving to them). The vault passphrase and
  recovery key are never sent over http, even then.
- Only the phone's system certificate authorities are trusted. For a server with a self-signed or
  private-CA certificate, the app shows the certificate's SHA-256 fingerprint the first time and
  remembers it, for that host and port only, once you approve it (Settings → Forget approved certificates resets this).
- Redirects are not followed, and server replies are size-limited.

## Server API

The app expects these endpoints (all JSON, `Authorization: Bearer <token>`, errors as `{"error": "…"}` or `{"detail": "…"}`):

| Method | Path | Notes |
| --- | --- | --- |
| POST | `/api/auth/login` | `{username, password, device?}` → `{token, expires_at, unlocked, device}`; the device token is stored encrypted and sent back so a stranger's wrong guesses can't lock this phone out |
| POST | `/api/auth/logout` | revokes the token |
| GET | `/api/auth/sso/{id}/start` | opened in a Custom Tab with `app_redirect=scanvault://sso`, `app_state`, `code_challenge` |
| POST | `/api/auth/sso/exchange` | `{ticket, code_verifier}` → same as login |
| GET | `/api/status` | `{setup_complete, unlocked, authenticated, username, local_login, sso: [{id, name, preset}], …}` |
| POST | `/api/vault/unlock` | `{passphrase}` or `{recovery_key}` |
| GET/POST | `/api/patients` | list / create |
| GET/DELETE | `/api/patients/{id}` | |
| POST | `/api/patients/{id}/import` | multipart `file` (repeatable): DICOM or .zip |
| GET/POST | `/api/patients/{id}/studies` | list / create |
| GET/DELETE | `/api/studies/{id}` | |
| GET | `/api/studies/{id}/similar` | the patient's other studies, best match for comparison first |
| GET/POST | `/api/studies/{id}/files` | list / multipart upload (`file`, optional `kind`) |
| DELETE | `/api/files/{id}` | |
| GET | `/api/files/{id}/content` | decrypted bytes |
| GET | `/api/files/{id}/thumbnail` | JPEG, when `has_thumbnail` |

A locked vault answers data endpoints with HTTP 423 `{"code": "locked"}`; the app then shows the unlock dialog.

## Building

Requires JDK 17 and the Android SDK (API 35).

```sh
./gradlew testDebugUnitTest assembleRelease
# APK: app/build/outputs/apk/release/
```

**Install the release APK.** GitHub Actions builds a minified release APK on every push and pull
request (`scanvault-release-apk` artifact). It is signed when the repository has these Actions
secrets: `SCANVAULT_KEYSTORE_BASE64` (base64 of the .jks file), `SCANVAULT_KEYSTORE_PASSWORD`,
`SCANVAULT_KEY_ALIAS` and `SCANVAULT_KEY_PASSWORD`. Without them it is unsigned and Android won't
install it.

Pull requests also get a debug APK (`scanvault-debug-apk`) for testing. Don't use it for real
records: debug builds trust user-installed CA certificates and can be inspected with a debugger.

**Screenshots.** The images above come from Robolectric tests in
`app/src/test/java/com/scanvault/android/screenshots/` that run the real app against a stand-in
server with the same fictional sample vault as the web demo, in the dark theme. The normal test
run skips them. To render them:

```sh
./gradlew testDebugUnitTest -Pscreenshots --tests 'com.scanvault.android.screenshots.*'
# PNGs: app/build/screenshots/
```

The *README screenshots* workflow does the same on pull requests that touch those tests (or on
demand) and uploads the images as the `readme-screenshots` artifact. Copy them into
`docs/screenshots/` to update the README.

CI actions are pinned to commit SHAs and the Gradle wrapper jar is checked against Gradle's
published checksums.
