# RoyalShuffle for Android

Native Android prototype built with Kotlin and Jetpack Compose.

## Current scope

This first step provides a launchable application shell, foundational domain
models, and the True Random shuffle implementation. Spotify authentication and
Web API integration are intentionally not implemented yet.

## Package structure

- `domain/model`: platform-independent RoyalShuffle models.
- `domain/shuffle`: True Random behavior.
- `ui`: Compose application shell and theme.

Future implementation steps will add `auth`, `data/remote`, `data/local`, and
`data/repository` packages as those responsibilities acquire real code.

## Build

From this directory:

```shell
./gradlew testDebugUnitTest assembleDebug
```
