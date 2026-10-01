# RoyalShuffle for Android

Native Android prototype built with Kotlin and Jetpack Compose.

## Current scope

The prototype provides Spotify Authorization Code with PKCE, authenticated
playlist loading and selection, and private shuffled output playlist creation.
It loads every source item page, excludes local and unsupported/unusable items,
randomizes eligible track occurrences, selects Full or timed membership, then
optionally applies Artist Separation. It registers a new output as managed and
populates it in ordered batches.
Progress and partial writes are reported without automatic retry or rollback.
On later loads, marked output playlists missing from local state can be
recovered as managed or left unmanaged; partial population is not resumed.

## Session options

- Full retains all eligible occurrences, including duplicate tracks.
- 60M and Custom positive integer minutes consume a randomized prefix, including
  the full crossing track. A shorter source includes all eligible tracks.
- Timed sessions require positive integer durations for every eligible track,
  even tracks outside the selected prefix. Full remains available without durations.
- Custom minutes and duration arithmetic use arbitrary precision, with no product
  maximum. Invalid Custom input blocks submission.
- Artist Separation uses the first credited Spotify artist ID, after membership
  selection. It preserves exact occurrences and within-artist order, avoids
  adjacency when feasible, and balances unavoidable dominant-artist runs.
  Missing selected artist IDs fail before output creation.

Session mode, Custom input, and Artist Separation persist globally in the new
`royalshuffle_output_settings` preferences file. Fresh defaults are Full and
Artist Separation off. Existing authentication and playlist preferences are
unchanged; legacy managed IDs remain exclusions, without inferred bindings.

Output options are snapshotted on submission. A submission gate prevents
overlapping operations, including token refresh. Clearing/disconnecting cancels
active work and suppresses obsolete progress/results. Execution is foreground
only: interruption can leave a remote output behind, and process-death recovery
is not implemented. Every operation still creates a new private playlist.

## Package structure

- `domain/model`: platform-independent RoyalShuffle models.
- `domain/shuffle`: True Random behavior.
- `auth`: PKCE, session orchestration, and UI-facing authentication state.
- `data/local`: app-private authentication persistence.
- `data/remote`: Spotify token and current-user playlist endpoint access.
- `playlist`: playlist pagination, filtering, selection, and UI state.
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
