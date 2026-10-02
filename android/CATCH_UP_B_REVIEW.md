# Android Catch-Up B review

Catch-Up A is checkpointed; Catch-Up B is physically accepted and checkpointed
on `android/main` with message `Add accepted Android managed output lifecycle`.
210 local tests passed (142 retained regression tests plus 68 new tests), with
zero failures, errors, or skips. Physical testing was performed by David/Aurora;
the agent performed no live Spotify tests or APK installation. No version bump,
push, branch change, release artifact, or Catch-Up C work occurred. Elijah's device was untouched.

## Final physical acceptance

Catch-Up B is ACCEPTED based on the user-reported physical results:

- Pre-Catch-Up/legacy managed playlists remained exclusion-only and were not automatically adopted.
- First modern Kpop + preset60 prompted for creation-only naming and created `RND60M-Kpop`; repeating reused the same output without naming.
- External rename to `B REUSE TEST` survived reuse without RoyalShuffle renaming it back.
- Custom60 reused preset60 identity; toggling Artist Separation did not change identity.
- Custom30 created its own distinct identity as `RND30M-Kpop`.
- Unfollowed/deleted outputs could remain GET-readable. Corrected resolution requires fresh current-user listing evidence, or membership evidence after a listing miss; GET success alone never authorizes reuse.
- Diagnostics isolated temporary post-unfollow `/me/library/contains` HTTP403. Fresh exact-ID `/me/playlists` hits now authorize ordinary reuse without that redundant probe. Normal same-ID reuse passed twice consecutively without permission errors.
- An immediate absent-ID stale-resolution membership403 safely preserved the binding and performed no writes. Without auth, installation, or state changes, waiting tens of seconds to about one minute allowed normal stale resolution.
- This observed short Spotify propagation/testing window is accepted as an external edge case, not a RoyalShuffle defect. Explicit decision: no arbitrary delay or retry will be added in Catch-Up B.
- Canceling stale replacement naming created no replacement and preserved recovery state. Immediate Generate presented naming again.
- Accepting replacement created/populated `RND30M-Kpop`; immediate Generate reused and updated that replacement successfully.

Catch-Up B is ready for Catch-Up C after the user's reset. No Balanced Opportunity
or Catch-Up C implementation has begun. Historical investigation details are in
`CATCH_UP_B_STALE_BINDING_REVIEW.md`.

## 1. Catch-Up A checkpoint

Commit: `0b06867beac125a456fc71e55f007e90780c75db`

Message: `Add accepted Android session length and artist separation`

Only the following 19 implementation/test/documentation files were committed:

- `android/README.md`
- `android/app/src/main/java/com/royalshuffle/android/MainActivity.kt`
- `android/app/src/main/java/com/royalshuffle/android/data/local/SharedPreferencesOutputSettings.kt`
- `android/app/src/main/java/com/royalshuffle/android/data/remote/SpotifyOutputPlaylistApi.kt`
- `android/app/src/main/java/com/royalshuffle/android/output/ArtistSeparation.kt`
- `android/app/src/main/java/com/royalshuffle/android/output/CreateOutputPlaylist.kt`
- `android/app/src/main/java/com/royalshuffle/android/output/OutputContracts.kt`
- `android/app/src/main/java/com/royalshuffle/android/output/OutputDependencies.kt`
- `android/app/src/main/java/com/royalshuffle/android/output/OutputPlanner.kt`
- `android/app/src/main/java/com/royalshuffle/android/output/OutputSettings.kt`
- `android/app/src/main/java/com/royalshuffle/android/output/OutputViewModel.kt`
- `android/app/src/main/java/com/royalshuffle/android/ui/RoyalShuffleApp.kt`
- `android/app/src/test/java/com/royalshuffle/android/data/local/SharedPreferencesOutputSettingsTest.kt`
- `android/app/src/test/java/com/royalshuffle/android/data/local/SharedPreferencesPlaylistPreferencesTest.kt`
- `android/app/src/test/java/com/royalshuffle/android/data/remote/SpotifyOutputPlaylistApiTest.kt`
- `android/app/src/test/java/com/royalshuffle/android/output/CreateOutputPlaylistTest.kt`
- `android/app/src/test/java/com/royalshuffle/android/output/OutputPlannerTest.kt`
- `android/app/src/test/java/com/royalshuffle/android/output/OutputWorkflowTest.kt`
- `android/app/src/test/java/com/royalshuffle/android/ui/SessionStateCoordinationTest.kt`

The following pre-existing local work was intentionally left outside the commit
and was not changed by this task. The first six are tracked modifications; the
last two are untracked:

- `android/build.gradle.kts`
- `android/gradle/wrapper/gradle-wrapper.jar`
- `android/gradle/wrapper/gradle-wrapper.properties`
- `android/gradlew`
- `android/gradlew.bat`
- `android/settings.gradle.kts`
- `android/gradle/gradle-daemon-jvm.properties`
- `research/`

## 2. Catch-Up B file manifest

All of the following changes remain unstaged and uncommitted:

- `android/app/src/main/java/com/royalshuffle/android/data/local/SharedPreferencesPlaylistPreferences.kt`
- `android/app/src/main/java/com/royalshuffle/android/data/remote/SpotifyOutputPlaylistApi.kt`
- `android/app/src/main/java/com/royalshuffle/android/data/remote/SpotifyPlaylistApi.kt`
- `android/app/src/main/java/com/royalshuffle/android/data/remote/SpotifyWebApiClient.kt`
- `android/app/src/main/java/com/royalshuffle/android/output/CreateOutputPlaylist.kt`
- `android/app/src/main/java/com/royalshuffle/android/output/OrdinaryOutputRegistry.kt`
- `android/app/src/main/java/com/royalshuffle/android/output/OutputContracts.kt`
- `android/app/src/main/java/com/royalshuffle/android/output/OutputDependencies.kt`
- `android/app/src/main/java/com/royalshuffle/android/output/OutputViewModel.kt`
- `android/app/src/main/java/com/royalshuffle/android/playlist/PlaylistRepository.kt`
- `android/app/src/main/java/com/royalshuffle/android/ui/RoyalShuffleApp.kt`
- `android/app/src/test/java/com/royalshuffle/android/data/local/SharedPreferencesOrdinaryOutputRegistryTest.kt`
- `android/app/src/test/java/com/royalshuffle/android/data/local/SharedPreferencesPlaylistPreferencesTest.kt`
- `android/app/src/test/java/com/royalshuffle/android/data/remote/SpotifyOutputPlaylistApiTest.kt`
- `android/app/src/test/java/com/royalshuffle/android/output/CreateOutputPlaylistTest.kt`
- `android/app/src/test/java/com/royalshuffle/android/output/CreationNameStateTest.kt`
- `android/app/src/test/java/com/royalshuffle/android/output/ManagedOutputLifecycleTest.kt`
- `android/app/src/test/java/com/royalshuffle/android/output/OutputWorkflowTest.kt`
- `android/app/src/test/java/com/royalshuffle/android/output/TestOutputRegistry.kt`
- `android/app/src/test/java/com/royalshuffle/android/playlist/PlaylistRepositoryTest.kt`
- `android/app/src/test/java/com/royalshuffle/android/ui/SessionStateCoordinationTest.kt`
- `android/CATCH_UP_B_REVIEW.md`
- `android/CATCH_UP_B_STALE_BINDING_REVIEW.md`
- `android/README.md`

The original selection and Artist Separation algorithms are unchanged.
Existing tests were adapted for required registry injection, naming decisions,
concise defaults, and cancellation's nullable result. No regression tests were removed.

## 3. Binding schema and migration

The existing `royalshuffle_playlists` preferences file gains the JSON string key
`ordinary_output_bindings`. Version 1 contains:

```json
{
  "schema_version": 1,
  "bindings": [
    {"source_id": "sourceSpotifyId", "session": "full", "output_id": "outputSpotifyId"},
    {"source_id": "sourceSpotifyId", "session": "60", "output_id": "timedSpotifyId"}
  ]
}
```

Absence means no bindings; reading legacy state creates no migration writes.
Existing managed IDs, declines, and selected source remain intact. Authentication
and global option storage are unchanged. Legacy IDs remain unbound exclusions;
names and descriptions never establish associations. Corrupt/conflicting or
unsupported schemas fail closed without rewriting them.

## 4. Exact identity rules

Identity is `(source playlist ID, session key)`. Full uses `full`; timed sessions
use canonical positive integer minutes, including arbitrary-precision Custom
values. Preset 60M and Custom 60 share a key. Different sources and minute values
are isolated. Artist Separation is not part of identity. Output IDs cannot be
shared by unrelated bindings or coincide with any bound source.

## 5. Creation-only naming

The Compose dialog appears only when creation is necessary, after planning and
resolution. Defaults are `RND-<source>`, `RND60M-<source>`, and `RND<N>M-<source>`.
The default is selected and focused for replacement. Draft edits survive activity
recreation; blank input shows an error without submission. Cancel aborts with no
remote writes or binding changes. There is no Android Auto character cap.

New outputs use `Randomized by RoyalShuffle | Spotify Companion`. Recovery still
recognizes `True-randomized copy generated by RoyalShuffle`. Reuse changes neither
name nor description.

## 6. Reuse and resolution

The workflow searches paginated normal playlist discovery for the bound ID. If
absent, it makes an authenticated direct lookup of that exact ID. Listing and
direct resolution preserve the current Spotify name. Reuse never requests naming
and never creates another output. Invalid lookup identities abort. Fresh exact-ID
current-user listing presence authorizes reuse. Only after a listing miss and
successful direct lookup must an exact-URI library-membership check confirm saved state.

## 7. Confirmed-missing recovery

Direct HTTP 404 or explicit library-membership false permits replacement.
Listing absence alone does not. A readable, still-owned but unfollowed playlist
is stale for this binding. See [stale-binding repair](CATCH_UP_B_STALE_BINDING_REVIEW.md).
Connectivity, authentication, permission, rate/quota, server, malformed-response,
and ambiguous failures preserve the association and abort without output writes.

## 8. Guarded replacement and collisions

A stale association remains until a replacement name is accepted, creation
succeeds, and the new binding is saved. Expected-old-ID checks run before creation
and again during binding. A concurrent change cannot be overwritten. If a change
or persistence failure occurs after remote creation, the new playlist is left
unpopulated and the UI reports that condition. Unrelated bindings are preserved.
Old exclusion IDs are retained, including stale IDs, so no referenced or legacy
ID is accidentally removed. Creation cannot implicitly adopt a legacy managed ID.

## 9. Population semantics

New outputs are durably bound/registered before ordered append. Reused outputs
receive an empty-URI PUT clear, then ordered POST batches of at most 100. Validation
finishes before destructive writes. Replacement is not remotely atomic.

Results distinguish Created and Updated. Clear failure reports zero acknowledged
new items and uncertainty about previous contents. Append failure reports only
acknowledged batches, explicitly noting that previous contents were cleared for
an update. Partial outputs remain bound for fresh regeneration; no automatic
rollback or ambiguous write replay is introduced.

## 10. Concurrency and registry safety

Catch-Up A's synchronous submission gate, captured source/options, cancellation,
and generation-based result suppression remain in force, including while naming.
Obsolete naming confirmations/cancellations are ignored by request identity.

Binding and exclusion changes share one checked SharedPreferences commit on IO.
A process-wide monitor serializes read/modify/write across wrappers, including
legacy managed-ID and decline updates. Failed binding commits fail closed across
wrappers until process restart, even if Android updated SharedPreferences memory
before reporting disk failure. This is a local registry mechanism, not a remote
transaction or cross-device coordination system.

## 11. Added tests

68 new tests cover versioned storage, old-key preservation, canonical identities,
concurrent wrappers, failed-commit memory behavior, exact-ID lookup, naming and
stale request state, cancellation, external rename, 404 and non-404 recovery,
expected-old mismatches before/after creation, collisions, partial clear/append,
ordered duplicate-preserving batches, quota and ambiguous writes, and both
managed descriptions. All retained 142 tests pass.

## 12. Validation

From `C:\Users\royal\RoyalShuffle\android`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_USER_HOME = 'C:\Users\royal\.gradle'
.\gradlew.bat testDebugUnitTest --offline
.\gradlew.bat lintDebug --offline
```

- Unit suite: success, 210 tests, 0 failures, 0 errors, 0 skipped. Debug Kotlin and
  test compilation completed; no APK build/install was requested or performed.
- Lint: exit 1, 2 errors and 20 warnings. Both error reports are the pre-existing
  `RollingFileDiagnosticLogger.kt:14` use of `Instant.now()` with minimum API 23.
  No new lint errors. New advisory findings at the binding commit are
  `ApplySharedPref` and `UseKtx`; explicit checked commit is intentional because
  asynchronous apply or a Unit-returning convenience wrapper cannot supply the
  durability result required before population. Existing diagnostics, dependency,
  manifest, and KTX suggestions remain outside this phase.
- `git diff --check`: success, no whitespace errors.
- Combined `testDebugUnitTest lintDebug --offline` also ran; tests completed and
  lint failed for the same existing API errors. Final standalone tests passed.

Reports: `android/app/build/reports/tests/testDebugUnitTest/index.html` and
`android/app/build/reports/lint-results-debug.html`.

## 13. Deviations

No product-contract deviations. Missing-output recovery is more conservative
than removing the stale association before creation: it keeps that association
until a new ID can replace it through a checked durable commit.

## 14. Remaining risks and decisions

No open product decisions. David/Aurora physical-device acceptance is complete.
The short post-unfollow Spotify propagation window is accepted without B delays/retries.
Remote clear/append can leave incomplete content; lost creation responses
or interruption can leave an unregistered remote playlist. Durable process-death
and delivery recovery remain deliberately deferred. The existing API 23-25
diagnostics compatibility issue remains unchanged. No release/distribution or
Elijah-device action was taken.

## 15. Final git status

Catch-Up B intended source/tests/docs are checkpointed. The index is empty after
commit. Only the following unrelated pre-existing local changes remain; they were
excluded and preserved without reset, clean, stash, or modification:

```text
 M android/build.gradle.kts
 M android/gradle/wrapper/gradle-wrapper.jar
 M android/gradle/wrapper/gradle-wrapper.properties
 M android/gradlew
 M android/gradlew.bat
 M android/settings.gradle.kts
?? android/gradle/gradle-daemon-jvm.properties
?? research/
```
