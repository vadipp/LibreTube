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

This branch follows the current default branch: Piped is an account/feed sync
provider, while video extraction is local. It does not restore the older Piped
playback mode from LibreTube 32.1 or add support for older Android versions.

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
expired server session for a useful error. Download a video and verify playback
after disabling networking; downloads in this branch use local video extraction.
