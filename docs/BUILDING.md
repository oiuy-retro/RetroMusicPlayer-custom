# Building the custom build

This covers building a signed release APK of this fork from source on Linux or macOS. Windows works
the same way with `gradlew.bat`.

## Requirements

| What | Version | Notes |
|---|---|---|
| JDK | **21** | The project compiles with `sourceCompatibility`/`jvmTarget` 21. JDK 17 fails. Any JDK 21 distribution works (e.g. Temurin 21). |
| Android SDK | platform **android-36**, build-tools 36 | `compileSdk = 36`, `targetSdk = 36`, `minSdk = 24` |
| Gradle | wrapper (8.14.2) | Use `./gradlew`. It downloads the right Gradle automatically. |
| Disk / RAM | ~4 GB free, 4 GB+ RAM | The first build downloads dependencies. |

Point Gradle at the SDK with either:

- the `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) environment variable, or
- a `local.properties` file in the repo root containing `sdk.dir=/path/to/android-sdk`.
  It's git-ignored.

If your default `java` isn't 21, set `JAVA_HOME` for the build:

```bash
export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
java -version   # should say 21
```

Installing the SDK pieces with the command-line tools:

```bash
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
```

## Flavors and variants

`app/build.gradle.kts` has two product flavors:

- **`fdroid`**: no Google Play services, billing or Chromecast. **This is what the custom releases use.**
- `normal`: includes Play billing, Play review, feature delivery and Cast. It isn't used or
  tested for this fork.

Common tasks:

```bash
./gradlew assembleFdroidRelease          # signed release APK (what gets published)
./gradlew assembleFdroidDebug            # debug APK, package name gets a ".debug" suffix
./gradlew :app:compileFdroidDebugKotlin  # quick compile check only
./gradlew lint                           # upstream's lint check (also run by upstream CI)
```

Release builds run R8 (`isMinifyEnabled = true`) and resource shrinking. The app's model classes
are already kept by `app/proguard-rules.pro`, and the custom changes didn't need extra keep rules.

Output:

```
app/build/outputs/apk/fdroid/release/app-fdroid-release.apk
```

## Signing

The build script reads **`retro.properties`** from the repository root (via
`rootProject.file("retro.properties")`):

```properties
storeFile=/path/to/your-release-keystore.jks
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

- Start from the template: `cp retro.properties.example retro.properties`.
- `storeFile` may be absolute, or relative to the `app/` module directory.
- `retro.properties`, `*.jks` and `*.keystore` are in `.gitignore`. **Never commit them**, and
  don't paste passwords into issues, commit messages or release notes.
- If `retro.properties` doesn't exist, both release and debug builds are signed with the Android
  **debug** key. The APK still installs, but it can't update an install signed with the release key.
- When `retro.properties` *does* exist, the `debug` build type is signed with the release key too.
  It still gets the `.debug` package suffix, so it installs alongside the release build.

### Creating a keystore (first time only)

```bash
keytool -genkeypair -v -keystore my-release.jks -alias my-key \
  -keyalg RSA -keysize 4096 -validity 10000
```

Keep this file and its passwords backed up somewhere safe. **Every future update must be signed
with the same key**; otherwise Android refuses to install it over the existing app, and the only
way out is uninstalling (which loses the app's data).

### Verifying the APK

```bash
apksigner verify --verbose --print-certs app/build/outputs/apk/fdroid/release/app-fdroid-release.apk
aapt2 dump badging app/build/outputs/apk/fdroid/release/app-fdroid-release.apk | head -1   # versionCode / versionName
```

`apksigner` should print `Verifies` and the SHA-256 digest of your certificate. Compare it with
previous releases to make sure you used the same key. Because `minSdk` is 24, a v2 signature alone
is enough.

## Versioning

`versionName` follows upstream (currently `7.0.3`). Each custom release bumps **`versionCode`** in
`app/build.gradle.kts` by at least 1, so it installs as an update (10703 → 10704 → 10705 so far).
See [UPDATING_FROM_UPSTREAM.md](UPDATING_FROM_UPSTREAM.md#4-bump-the-versioncode) for how to pick
the number after merging a new upstream version.

## Troubleshooting

**`error: invalid source release: 21`, or Kotlin complaining about JVM target 21**
You're building with an older JDK. Install JDK 21 and set `JAVA_HOME` as shown above.

**`SDK location not found`**
Set `ANDROID_HOME`, or create `local.properties` with `sdk.dir=...`.

**`Failed to find target with hash string 'android-36'` / missing build-tools**
Install them with `sdkmanager "platforms;android-36" "build-tools;36.0.0"`.

**The release APK is signed with the debug key**
`retro.properties` wasn't found or has a typo in a key name. The keys must be exactly
`storeFile`, `storePassword`, `keyAlias` and `keyPassword`. The file must be in the repository root,
not in `app/`.

**`Keystore was tampered with, or password was incorrect` / `Cannot recover key`**
Wrong `storePassword` or `keyPassword`, or the wrong `keyAlias`. List the aliases with
`keytool -list -keystore your.jks`.

**R8 / minify errors (`Missing class ...`)**
If new code uses reflection or is loaded by name, add `-keep` rules to `app/proguard-rules.pro`.
Don't disable minification.

**`GOOGLE_PLAY_LICENSE_KEY missing` in BuildConfig**
That's expected: it comes from an optional `public.properties` that only upstream's Play builds
use. The fdroid flavor doesn't need it.

**Android says "App not installed" / "package conflicts with an existing package"**
Another copy with the same package name but a different signing key is installed (Play Store,
F-Droid, or a build signed with a different keystore). Uninstall it first, or sign with the same
key as the installed copy. A lower versionCode than the installed one also fails.

**Gradle runs out of memory**
Close other heavy apps, or raise `org.gradle.jvmargs` in your **user** `~/.gradle/gradle.properties`
rather than the checked-in one.
