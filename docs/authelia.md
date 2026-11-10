# Piped instances protected by Authelia

In Account settings choose Piped as the synchronization provider, select the
instance API, and open Login. Enter the account username and password and the
optional Authelia portal URL, for example `https://video.example.com/authelia/`.
The portal must be the URL you would normally visit to sign in, including any
subpath. Do not enter the API URL in this field unless it also hosts the portal.

The same credentials are submitted first to Authelia and then to Piped. This
requires the two accounts to have matching credentials. Only one-factor Authelia
policies are supported; installations requiring a second factor need interactive
login instead. Leaving the portal field empty preserves ordinary Piped login.
Registration through an Authelia-protected instance is not supported here.

The app requests “remember me”. Session lifetime remains controlled by the server;
it does not request an infinite lifetime. The shared username and password are
retained encrypted alongside the session. If the gateway rejects a session, the
app signs in to Authelia and Piped again and retries the request once with the new
cookie and Piped token. Concurrent expired requests share the refresh operation.
A failed automatic login stops further attempts until you sign in manually (or
restart the app), avoiding a loop of invalid-password requests. The Piped token
and Authelia cookie are independent. A server restart can invalidate the Authelia
session even if its cookie has not expired; automatic login handles that case.

Credentials and session cookies are encrypted with an Android Keystore key and saved outside
Android's backup directories. They are restricted to HTTPS and the exact
configured API/portal origins, plus the image-proxy origin advertised by the
authenticated instance configuration. Cookies are never automatically sent to
other sibling sites, YouTube, SponsorBlock or other external services. Cookie
renewal responses update the stored session. Signing out or switching account
instances removes the local credentials and Authelia session; it does not revoke sessions on other
devices or delete existing offline downloads. Debug logging excludes login bodies
and response bodies.

The authentication branch follows the current default branch. The separate
playback-restoration branch adds **Use Piped for browsing, playback and downloads**
under Player settings. Select Piped and sign in in Account settings before
enabling it. It uses the same instance API for browsing and stream extraction,
keeps the returned media proxy URLs and their signatures intact, and sends
Authelia cookies through the player, HLS/subtitles, images and downloads.
Piped mode does not silently switch to local extraction: direct YouTube media
URLs or redirects are rejected. Restart any currently playing video or active
download after changing the mode. Turning the option off retains upstream local
playback. Both extraction modes support Android 7.1 (API 25) or newer.

Tests cover TLS cookie scope, persistence, credential encoding, redirect handling,
first-factor-only rejection, and expiration errors using a local HTTPS server:

```sh
./gradlew -p tools/auth-session-tests test
./gradlew testDebugUnitTest assembleDebug
```

The HTTP/session suite uses the production source with OkHttp’s JVM variant,
without the Android SDK or Room/KSP processing. It lives in the standalone
project because Android’s OkHttp artifact requires a real Android context for
its public-suffix assets (ordinary Android JVM stubs cannot provide one):

```sh
./gradlew -p tools/auth-session-tests test
```

Device verification: log in, load account playlists/feed and thumbnails, force-stop
and reopen the app, then log out and verify a fresh login is required. Test an
expired server session for automatic login. On the playback-restoration branch,
enable Piped mode, play and seek a video, and inspect server access to confirm
media requests reach the instance proxy. Download it and verify playback after
disabling networking. The authentication-only branch uses local extraction.

## Fork builds and updates

Trusted branch builds publish a GitHub prerelease with a directly downloadable
APK and SHA-256 checksum. Actions artifacts remain ZIP downloads; use the release
asset to avoid extraction. The APK filename includes the app version, CI build
number, branch and commit. CI also embeds its build number in the Android version
name and a monotonically increasing version code. PR builds are test builds and
are not published as releases.

Configure these repository Actions secrets before publishing:

- `ANDROID_SIGNING_KEY`: base64-encoded private Java/PKCS12 signing keystore.
- `ANDROID_KEY_ALIAS`: the key alias in that keystore.
- `ANDROID_KEYSTORE_PASSWORD`: the keystore password.
- `ANDROID_KEY_PASSWORD`: the private key password.

All published branch builds use this same key. Publishing fails if a secret is
missing or signature verification fails. Keep a private backup of the keystore;
replacing it prevents Android from accepting updates to existing installations.
The debug package remains `com.github.libretube.debug`. Older builds signed with a
temporary runner key require a one-time uninstall before switching to this key;
export any wanted app data first. Later builds can update in place and retain
configuration. The signing secrets are not used for PRs and are never uploaded
as artifacts. The signing script removes its temporary keystore on exit.

Master builds include two signed APKs: `debug` is debuggable and installs as
`com.github.libretube.debug`; `release` uses code/resource shrinking and installs
as `com.github.libretube`. Both include the same functionality and CI version,
and both use the saved signing key. They can coexist; their app data is separate.
The release APK cannot update an official upstream install signed by another key.
Feature branches publish debug APKs only.

## Android 7.1 compatibility

The fork restores guards around Oreo-only picture-in-picture, HDR and
cache-quota APIs. Android 7 uses Coil's default disk-cache sizing and has PiP
disabled. Core-library desugaring remains enabled for time/NIO APIs used by
playback and downloads. Only API 25 resources additionally trust the bundled,
unmodified ISRG Root X1 certificate from Let's Encrypt; API 26+ keeps its existing
trust configuration. TLS chain and hostname verification remain enabled.

CI runs strict NewApi/InlinedApi lint, tests, assembly and signing, then installs
and opens the APK twice on a GitHub-hosted API 25 x86 emulator before publishing.
That startup check does not certify BYD media decoding, authentication, background
playback or offline downloads. Verify those on the target device.
