# RoyalShuffle for Android

Native Android prototype built with Kotlin and Jetpack Compose.

## Current scope

The prototype provides a launchable application shell, foundational domain
models, the True Random shuffle implementation, and Spotify Authorization Code
with PKCE. Playlist Web API operations are intentionally not implemented yet.

## Package structure

- `domain/model`: platform-independent RoyalShuffle models.
- `domain/shuffle`: True Random behavior.
- `auth`: PKCE, session orchestration, and UI-facing authentication state.
- `data/local`: app-private authentication persistence.
- `data/remote`: Spotify token endpoint access.
- `ui`: Compose application shell and theme.

## Spotify prototype configuration

1. Add `com.royalshuffle.android.auth://callback` to the redirect URI allowlist
   for the Spotify app.
2. Add `com.royalshuffle.android` as its Android package name.
3. Put the client ID (never the client secret) in the untracked
   `android/local.properties` file:

   ```properties
   SPOTIFY_CLIENT_ID=your_client_id
   ```

The prototype requests `playlist-read-private` and `playlist-modify-private`.
It opens Spotify authorization in a Custom Tab and handles the callback through
an Android browsable intent filter.

## Build

From this directory:

```shell
./gradlew testDebugUnitTest assembleDebug
```
