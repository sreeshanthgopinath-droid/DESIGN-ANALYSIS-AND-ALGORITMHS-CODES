# Privacy and security

## Principles

1. **Transparent.** Capture runs only after explicit consent, and only while you have it switched on. A persistent notification with **Pause/Stop** is shown the whole time, and Android's own camera and screen-sharing indicators are never suppressed. There is no hidden mode.
2. **User-controlled.** Start, pause, resume and stop are always one tap away. Screen sharing needs Android's consent dialog for every session, and the app never reopens that dialog from the background.
3. **Minimal.** Still images only. No audio, video, location, contacts or device identifiers (IMEI or serial number). No analytics or advertising SDKs. Nothing is shared with third parties.
4. **Encrypted at rest and in transit.** Images exist in plaintext only in memory. They are AES-256-GCM encrypted before they touch the disk, and the same encrypted bytes are what get uploaded.
5. **Honest.** Failures are recorded as failures, with a reason.

## Other people

Camera photos and screenshots can capture **other people**, their faces, conversations, messages, one-time codes and other private data. Use the app only on your own phone, tell people around you, and follow your local laws (for example India's DPDP Act 2023). Never install it on someone else's device.

## Encryption design

```
               ┌─────────────────────────────────────────────┐
               │ Android Keystore (StrongBox if available,    │
               │ otherwise TEE): AES-256-GCM wrapping key     │
               │ non-exportable · never in app memory         │
               └───────────────┬─────────────────────────────┘
                               │ wraps
 recovery code (160-bit,       ▼
 shown once) ──PBKDF2──▶ KEK ──wraps──▶ DEK(s): random AES-256 data keys
 310k iter, 128-bit salt                     │
                                             ▼  AES-256-GCM, fresh 96-bit nonce per file
                                  image.jpg.enc / thumb.enc  (PCTE v1 envelope)
```

- **PCTE v1 envelope**: `"PCTE" | version | key-id | nonce(12) | ciphertext+tag(16)`. The authenticated data covers the header and a context (`capture:<id>` or `thumb:<id>`). A file swapped between records, an edited header, or a wrong key all fail to decrypt; they never decrypt to wrong data.
- **Unique nonces**: every file gets a fresh `SecureRandom` 96-bit nonce and a new `Cipher` instance.
- **Local keys**: each DEK is stored only in Keystore-wrapped form, in no-backup storage. The wrapping key does not require the phone to be unlocked, because scheduled captures must be encrypted while it is locked.
- **Recovery**: the keyring (DEKs wrapped by the recovery-code KEK) is stored locally and in Firestore `users/<uid>/vault/keyring`. On a new phone, sign in, enter the recovery code, and every cloud image becomes readable again. **Without the recovery code, nobody can decrypt the cloud images, including you.** You can generate a new code in Settings; the old code then stops working.
- **Recovery-code display** uses a `FLAG_SECURE` window, so Android screenshots and this app's own screen capture cannot record it.
- **Upload gate**: before any image is uploaded, the app checks that the cloud keyring can recover the key used. If the account already holds another phone's keyring, uploads pause until you enter that account's recovery code. This prevents uploads that could never be recovered.

## Data flows

| Data | Where | Encrypted |
|---|---|---|
| Images and thumbnails | `files/captures/…` on the phone | Yes (AES-256-GCM) |
| Capture records and diagnostics | Room database in app-private storage | Android file-based encryption; contains metadata only |
| Settings and schedule state | SharedPreferences in app-private storage | Android file-based encryption |
| Cloud images | Firebase Storage, owner-only rules | Yes (same ciphertext) and TLS in transit |
| Cloud metadata | Firestore, owner-only rules | TLS; contains times, sizes and paths, no image data |
| Device backups | Disabled (`allowBackup=false`, data-extraction rules exclude everything) | — |

**Export** is the only action that writes plaintext. It asks for confirmation, writes a JPEG to a location you choose, and records an entry in the diagnostic log.

## Firebase hardening

- Owner-only Storage and Firestore rules; everything else is denied ([../firebase](../firebase)).
- Uploads must be `application/octet-stream` named `*.jpg.enc`, under 25 MB, at the expected path.
- No download URLs are ever created; reads require the owner's authenticated SDK session.
- App Check: Play Integrity in release builds, debug provider in debug builds.
- No administrator or service-account credentials are in the APK. `google-services.json` holds only public identifiers and is git-ignored.

## Deletion and retention

| Action | Effect |
|---|---|
| Delete one capture → *Phone only* | Removes the encrypted image and thumbnail from the phone |
| Delete one capture → *Phone and cloud* | Also deletes the Storage object and Firestore document (retried when online) |
| Retention (phone) | Deletes phone copies older than N days. While cloud backup is on, a phone copy is deleted only after its cloud copy is verified. |
| Retention (cloud) | Deletes cloud copies older than N days |
| Free space after verified upload (opt-in) | Deletes the full-size phone copy once the cloud copy is verified; keeps the thumbnail |
| Delete all data on this phone | All images, records and logs on the phone |
| Delete all cloud data | All objects, metadata and the keyring of your account; turns cloud backup off |
| Delete cloud account | All cloud data, then the Firebase user (password re-entry required) |
| Uninstall | All local data and the Keystore key |

## Reporting a security problem

Open a private issue on the repository and include the steps to reproduce. Please do not attach real captures.
