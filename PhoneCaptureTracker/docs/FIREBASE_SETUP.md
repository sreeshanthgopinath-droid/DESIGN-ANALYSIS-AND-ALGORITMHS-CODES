# Activating encrypted cloud backup (Firebase)

The repository contains **no Firebase credentials**. Without `app/google-services.json`, the build applies no Google Services plugin and the app runs in **local-only mode**. Everything works except the account and uploads.

## 1. Create the project

1. Open <https://console.firebase.google.com> → **Add project** (Google Analytics is not needed).
2. **Cloud Storage for Firebase** needs the **Blaze (pay-as-you-go)** plan for default buckets created since late 2024. Check the current pricing page, and set a **budget alert** under *Google Cloud → Billing → Budgets & alerts*.

## 2. Register the Android app

1. *Project settings → Your apps → Add app → Android*.
2. Package name: `com.phonecapture.tracker`.
3. Add the **SHA-256** fingerprint of every signing key you use (needed for App Check):
   ```bash
   ./gradlew :app:signingReport      # debug key
   keytool -list -v -keystore your-release.jks -alias your-alias   # release key
   ```
4. Download **google-services.json** and copy it to `PhoneCaptureTracker/app/google-services.json`. It is git-ignored; never commit it.

## 3. Enable the services

| Service | Setting |
|---|---|
| **Authentication** | *Sign-in method → Email/Password → Enable* |
| **Firestore** | *Create database → Production mode*, in a region near you (e.g. `asia-south1` for Chennai) |
| **Storage** | *Get started → Production mode*, same region |

## 4. Deploy the security rules

```bash
npm install -g firebase-tools
firebase login
cd PhoneCaptureTracker/firebase
firebase use --add            # choose your project
firebase deploy --only firestore:rules,storage
```

The rules allow only the signed-in owner to read, write, list or delete `PhoneCaptureTracker/<uid>/…` and `users/<uid>/…`. They accept only encrypted `*.jpg.enc` uploads under 25 MB, with strictly validated metadata, and deny everything else. No public download links are created.

## 5. App Check

- **Debug builds** install the debug provider. Run the app once, find `DebugAppCheckProvider` / "debug secret" in Logcat, and add the token under *App Check → Apps → Manage debug tokens*.
- **Release builds** use **Play Integrity**. Play Integrity only gives a passing verdict for apps installed from Google Play, including the free *internal testing* track. A sideloaded release APK fails App Check.
- Register the app under *App Check*, watch the metrics, and only then click **Enforce** for Storage and Firestore.

## 6. Rebuild and sign in

```bash
./gradlew assembleDebug
```

In the app: *Settings → Cloud backup* → create an account or sign in → turn on **Upload captures**. By default uploads only use Wi-Fi (unmetered networks). The first upload also stores your **recovery keyring** (keys wrapped with your recovery code) in `users/<uid>/vault/keyring`.

## What is stored in Firebase

| Location | Content |
|---|---|
| Storage `PhoneCaptureTracker/<uid>/<yyyy>/<MM>/<dd>/<Front\|Rear\|Screenshots>/<UTC-stamp>_<tag>_<id>.jpg.enc` | AES-256-GCM encrypted image (PCTE v1 envelope) |
| Firestore `users/<uid>/captures/<id>` | Category, UTC times, storage path, size, SHA-256 of the ciphertext, key id, dimensions. No image data. |
| Firestore `users/<uid>/vault/keyring` | Image keys wrapped with a key derived from your recovery code (PBKDF2-HMAC-SHA256, 310,000 iterations). No plaintext keys. |

Folder dates are UTC, so they never depend on the phone's time-zone setting.

## Verification of every upload

After `putBytes`, the app reads the object metadata back and compares the **size** and **MD5** with the local encrypted file. Only then does it write the Firestore document and mark the capture *Uploaded · verified*. A mismatch deletes the remote object and retries.

## Removing everything

- In the app: *Settings → Privacy & data → Delete all cloud data* or *Delete cloud account*.
- In the console: delete the Storage folder `PhoneCaptureTracker/<uid>`, the Firestore documents under `users/<uid>`, and the user under *Authentication*.
