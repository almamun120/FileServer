# Signed release APK – one-time setup

1. Create a keystore (keep the file and passwords safe – you need the SAME key for every future update):

```bash
keytool -genkeypair -v -keystore release.jks -alias fileserver \
  -keyalg RSA -keysize 2048 -validity 10000
```

2. Convert it to base64:

```bash
base64 -w0 release.jks > release.jks.b64      # Linux
base64 -i release.jks | tr -d '\n' > release.jks.b64   # macOS
```

3. On GitHub open **Settings > Secrets and variables > Actions > New repository secret** and add:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | contents of `release.jks.b64` |
| `KEYSTORE_PASSWORD` | the keystore password |
| `KEY_ALIAS` | `fileserver` (the alias you used) |
| `KEY_PASSWORD` | the key password |

4. Push to `main`/`master`, or run **Actions > Build APK > Run workflow**.
   APKs appear under the run's **Artifacts** (`FileServer-debug`, `FileServer-release`).
5. To publish a Release: `git tag v1.1 && git push origin v1.1` – both APKs are attached automatically.

Never commit `release.jks` (it is already ignored in `.gitignore`).

Without the secrets the workflow still works: debug is built normally and the release APK is
produced as `...-release-UNSIGNED.apk` (cannot be installed until signed).

Local signed build:

```bash
export KEYSTORE_FILE=/path/release.jks KEYSTORE_PASSWORD=... KEY_ALIAS=fileserver KEY_PASSWORD=...
./gradlew assembleRelease
```
