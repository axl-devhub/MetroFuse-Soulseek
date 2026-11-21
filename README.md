# Soulseek plugin for MetroFuse

Play songs in lossless or 320 kbps from the Soulseek network in [MetroFuse](https://github.com/956tris/MetroFuse). The plugin downloads songs from Soulseek in the background and plays the downloaded file instead of the stream, offline too.

It is a native Kotlin plugin for Android and desktop. The Soulseek client is plain Kotlin on JVM sockets, with no Python, JNI or native libraries.

## How it works

- **Audio source.** When a song plays, the plugin answers with its downloaded Soulseek file if there is one. Put Soulseek first in MetroFuse's audio provider priority so downloaded songs play from it; songs it does not have fall through to your other sources.
- **Background downloads.** For the current song and the next two in the queue, the plugin searches Soulseek and downloads the best match: the title and artist must be in the file path, the length must be within 3 seconds, and remixes, live and other versions are skipped unless the title asks for them. A song that is not downloaded yet plays from another source that time and from Soulseek after that. Turn on **Wait up to 25 seconds** to wait for the download instead.
- **Search.** With Soulseek selected as the Home source, search lists Soulseek files as tracks; playing one downloads it.
- **Home.** The Soulseek Home source lists the songs downloaded so far.

Downloads are kept in `~/Music/Soulseek` on desktop and in MetroFuse's private storage on Android.

## Install

1. Download `soulseek.apk` (Android) or `soulseek.jar` (desktop) from [Releases](../../releases).
2. In MetroFuse, open **Settings → Plugins**, import the file and enable **Soulseek**.
3. Sign in (below), then move Soulseek to the top of the audio provider priority in the playback menu.

The plugin targets MetroFuse plugin API 1.1.0 and Compose ABI `kotlin-2.4.21_compose-1.13.0-beta02_api-1.1.0`. MetroFuse rejects it if those change; a rebuild is then needed.

## Sign in

Soulseek allows one login per account, so close other Soulseek clients that use the same account. An unused username creates a new Soulseek account.

- **Android and Windows:** select **Soulseek** in the Home source menu, open the account button, sign in, then switch back to your usual source. The plugin verifies the password with Soulseek, then MetroFuse stores it encrypted (Android Keystore, Windows DPAPI).
- **macOS and Linux:** MetroFuse has no encrypted credential store there and refuses plugin sign-in forms. Enter the username and password in the plugin's settings instead. The plugin verifies them with Soulseek, saves the password in the system keychain (`security` on macOS, `secret-tool` on Linux) and clears the password field. Until it is cleared, a second or two, the password is in MetroFuse's settings file. Without a keychain tool it is kept for the session only.

## Settings

| Setting | Default | Meaning |
| --- | --- | --- |
| Preferred quality | Lossless first | `Lossless first` (CD quality, then hi-res, then 320 kbps), `Hi-res first` or `320 kbps first`. Hi-res files are several times larger and download slower. |
| Download in the background | On | Downloads the current song and the next two. |
| Wait up to 25 seconds | Off | Waits for a song's Soulseek download before another source plays it. |
| Soulseek username / password | empty | macOS and Linux sign-in, see above. |

## Build

MetroFuse publishes no Maven SDK. Export the host API from a MetroFuse checkout, then build:

```sh
(cd ../MetroFuse && ./gradlew :plugin-api:desktopJar --no-configuration-cache)
./gradlew :native:desktopTest :native:packagePluginJar :native:packagePluginApk \
  -PmetrofuseApiJar=../MetroFuse/plugin-api/build/libs/plugin-api-desktop-1.1.0.jar --no-configuration-cache
```

The outputs are `build/plugin-examples/soulseek.jar` and `build/plugin-examples/soulseek.apk`. Android packaging needs `ANDROID_HOME` or `sdk.dir` in an untracked `local.properties`, with platform `android-37.0` and build tools `37.0.0`. The toolchain is Java 25 and Kotlin 2.4.21.

## Status

The unit tests cover the protocol codec, the matching rules, track id mapping and the download index. The desktop JAR was loaded in MetroFuse's plugin engine with a real Soulseek account: it signed in through the host's login flow, searched, downloaded a 36 MB FLAC for a queued YouTube Music track in about 30 seconds and served it as a local file through the audio pipeline. The Android APK builds but has not been run on a device, and neither artifact has been tried in the MetroFuse app's UI yet.

Only download music you have the right to.

## License

GPL-3.0. See [LICENSE](LICENSE) and [native/SOURCE](native/SOURCE).
