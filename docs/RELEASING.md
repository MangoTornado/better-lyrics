# Releasing

Pushing a `v*` tag builds a signed APK and publishes it as a GitHub release.

```bash
git tag v0.2.0
git push origin v0.2.0
```

The version name comes from the tag; the version code from the run number, so each release
installs over the last one.

## One-time setup: the four secrets

The workflow needs the release key. **Keep the keystore itself out of the repository** —
losing it means you can never ship an update that upgrades an already-installed copy.

The local keystore lives at `~/.android/better-lyrics-release.jks`, and its path and
passwords are in `keystore.properties`, which is gitignored. It kept that name through the
rename to Melisma on purpose: the alias `better-lyrics` is written *inside* the keystore
file, so renaming either would mean generating a new key — and a new key can never update
an installed copy. A keystore you are creating fresh can be called anything. Read the values from there:

```bash
cat keystore.properties
```

Then add four repository secrets. With the [GitHub CLI](https://cli.github.com):

```bash
brew install gh && gh auth login          # if you do not have it yet

REPO=MelismaApp/melisma
KS=$(grep '^storeFile='     keystore.properties | cut -d= -f2-)
PW=$(grep '^storePassword=' keystore.properties | cut -d= -f2-)
AL=$(grep '^keyAlias='      keystore.properties | cut -d= -f2-)
KP=$(grep '^keyPassword='   keystore.properties | cut -d= -f2-)

base64 -i "$KS" | gh secret set KEYSTORE_BASE64   --repo "$REPO"
printf '%s' "$PW" | gh secret set KEYSTORE_PASSWORD --repo "$REPO"
printf '%s' "$AL" | gh secret set KEY_ALIAS         --repo "$REPO"
printf '%s' "$KP" | gh secret set KEY_PASSWORD      --repo "$REPO"
```

Or by hand, at **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -i ~/.android/better-lyrics-release.jks` — the whole output, one line |
| `KEYSTORE_PASSWORD` | `storePassword` from `keystore.properties` |
| `KEY_ALIAS` | `better-lyrics` |
| `KEY_PASSWORD` | `keyPassword` from `keystore.properties` |

The workflow decodes the keystore into the runner's temp directory, builds, verifies the
signature with `apksigner`, and deletes it again — including if the build fails.

## Building a signed APK locally

Nothing extra to do: `keystore.properties` is picked up automatically.

```bash
./gradlew :app:assembleRelease     # → app/build/outputs/apk/release/app-release.apk
```

Without `keystore.properties` (a fresh clone, someone else's machine) the same command
still works and produces an unsigned APK, so the build never depends on having the key.

## Key details

Signed with a 4096-bit RSA key, PKCS12 keystore, valid 30 years, v2/v3/v4 signature
schemes (v1 disabled — it is only needed below API 24, and minSdk here is 29).

Certificate SHA-256, so you can confirm an APK is yours:

```
09:7F:D1:29:69:C0:A2:61:2B:46:3D:13:F5:96:35:16:D0:CD:70:86:DC:93:BA:F4:DA:2C:E7:F1:69:84:05:64
```

Check any built APK against it with:

```bash
"$ANDROID_HOME"/build-tools/36.0.0/apksigner verify --print-certs app-release.apk
```

## If you lose the keystore

You cannot update existing installs — Android refuses an APK signed with a different key.
You would have to publish under a new application id and ask people to reinstall. Back
`~/.android/better-lyrics-release.jks` and its password up somewhere you trust.
