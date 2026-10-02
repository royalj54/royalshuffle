# Catch-Up B stale-binding repair

## Final physical acceptance and checkpoint

Catch-Up B is physically ACCEPTED. The final acceptance record is in
`CATCH_UP_B_REVIEW.md`. Ordinary same-ID reuse passed twice consecutively after
restoring listing-hit authorization. Post-unfollow membership403 preserved the
binding and performed no write; without auth/install/state changes, waiting tens
of seconds to about one minute allowed stale replacement naming. Cancel preserved
recovery and immediate Generate prompted again; accepted replacement populated
successfully and immediate reuse updated it successfully.

The user accepts this short Spotify propagation/testing window as an external
edge case, not a RoyalShuffle defect. No arbitrary delay or retry is to be added
in B. Automated acceptance remains 210 tests, zero failures/errors/skips, two
pre-existing Instant.now lint errors and 20 warnings, and clean diff check.
Documentation/checkpoint only followed acceptance; production code was unchanged.
The following sections are historical investigation records, including superseded
uncommitted/pending-retest statements. The accepted B checkpoint on android/main
uses message `Add accepted Android managed output lifecycle`; no push or release.

## Current decision: membership403 isolated, listing-hit reuse restored

This section supersedes the historical mandatory-membership decision below.
Physical diagnostics now confirm `managed output library membership`, READ,
HTTP403/PERMISSION on three attempts (October1 23:56Z and October2 00:11Z/00:33Z).
Source loading completed. The original clear failure was HTTP500/SERVER and is
separate; its refusal to retry remains correct.

The application-level defect is the unnecessary mandatory membership probe for
an output already returned by a fresh authenticated current-user listing. Spotify
[defines that listing](https://developer.spotify.com/documentation/web-api/reference/get-a-list-of-current-users-playlists)
as owned or followed playlists. In this architecture the persisted modern binding
already identifies an app-created output; the current listing supplies positive
user-list evidence. No name matching, arbitrary listed-playlist adoption, or cached
UI listing is involved. This supports restoring the original listing-first reuse path.

Request audit: GET `/v1/me/library/contains` uses one percent-encoded
`spotify:playlist:<ID>` URI; alphanumeric ID validation prevents query injection.
The transport sets `Authorization: Bearer <accessToken>` and `Accept: application/json`;
GET has no body. The same acquired token is used for successful source reads,
listing, and membership. Configured scopes are playlist-read-private and
playlist-modify-private; no scope changes. The actual device token's granted scopes
were not supplied and are not established by successful source reads alone.

The current endpoint documentation lists playlist URIs and playlist-read-private.
No documented extra save operation, playlist-age delay, or Development Mode
playlist restriction explaining these403s was found. Spotify's exact rejection
reason remains unknown: the available event records status/category, not the error
body or granted token scopes. Do not claim a server bug, scope deficiency, or
propagation delay as proven. The physical evidence proves the endpoint cannot
reliably be used as a mandatory ordinary-reuse gate in this environment.

Corrected decision tree:

1. Fresh authenticated `/me/playlists` pagination finds exact bound ID: reuse that
   ID/current name; no direct GET or membership probe.
2. Listing completes without that ID: direct GET exact ID. Direct404 allows guarded
   replacement naming; all other lookup errors abort and retain binding.
3. DirectGET success: current library membership true permits reuse; false enters
   guarded naming. Membership403/401/429/network/malformed/ambiguous failure aborts
   and retains binding. Listing absence is never sufficient for replacement.
4. Clear or append failures retain the existing partial-write refusal to replay,
   rollback, or automatically replace, including clearHTTP500.

This is positive listing evidence, not a fallback after403: no forbidden probe is
issued on the hit path. Listing and writes can race with external unfollow or
permission changes; neither listing nor membership reads guarantee the next write.
If the listing still returns an output immediately after unfollow, reuse may be
attempted and any failed write stops safely. No API snapshot-isolation guarantee
is assumed. Missing/unfollowed GET-readable coverage remains for the miss path.

Focused changes: `CreateOutputPlaylist.kt`, `ManagedOutputLifecycleTest.kt`, README
and both review reports. Two new tests prove newly bound Custom30/external rename
and later-page listing hits never invoke a forbidden membership endpoint. The
prior contradictory stale-listing test now asserts the supported positive-list
contract; membership-error cases remain on the absent-ID path. Other prior stale
recovery and adapter regressions retained.

Final validation: **210 tests**, zero failures/errors/skips. Lint: two existing
NewApi errors at `RollingFileDiagnosticLogger.kt:14`, 20 warnings, no new errors.
`git diff --check` clean. Index empty; B uncommitted; no version bump/push/install.

David exact retest:

1. Preserve auth/bindings, use this build, select Kpop + Custom30 and a currently
   saved bound output. Generate immediately: expect same-ID reuse without naming.
2. Repeat immediately, then externally rename and Generate: expect same ID/name,
   no duplicate and no `managed output library membership` request on listing hits.
3. Unfollow that output. Generate without changing identity. Once absent from the
   fresh Spotify listing, expect exact lookup plus membership (or direct404).
4. If naming appears, Cancel; Generate again, reject blank; accept `RND30M-Kpop`.
   Verify no writes before accepted naming and a different ID bound/populated.
5. Immediately Generate the replacement again: expect same-ID reuse without naming
   or membership probe when listed. Toggle Artist Separation and confirm same ID.
6. If absent-ID membership still returns403, verify no naming/write/binding change;
   export diagnostics immediately. This remaining failure is deliberately not
   converted into missing state. If an immediately unfollowed output is still
   listed, capture outcome; do not expect a stale listing to guarantee recovery.

Historical investigation records follow.

## Subsequent permission investigation (October 1, 2026)

Deprecated endpoint usage was **not** confirmed: this workspace already uses
`GET /v1/me/library/contains?uris=spotify%3Aplaylist%3A<ID>`. There is no
`followers/contains` request in Android source. The February 2026 Development Mode
[migration guide](https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide)
confirms that this is the replacement. Existing configured `playlist-read-private`
is the documented scope for playlist membership; no scopes or production code changed.

The generic permission message is the PERMISSION category, mapped from HTTP403
by the actual client. However the same message can arise from source-item fetch,
listing, direct lookup, or membership. Clear failures have the distinct partial-write
message. Without the failed-run diagnostic event, the membership request cannot
be identified as the source of the physical403. The exact physical root cause
remains undetermined; deprecated endpoint usage is ruled out in this workspace.
Do not infer propagation delays or insufficient scopes from a ten-minute recovery.
The installed binary's correspondence to this workspace is also not established
by the UI message.

Four adapter tests were added to `SpotifyOutputPlaylistApiTest.kt`: membership401
invalidates session without replay; membership403 retains status/category and
records the exact diagnostic operation; explicit429 repeats the same read;
QUOTA_EXCEEDED429 aborts without replay. Existing exact-URI true/false and strict
malformed-response tests and all seven previous regressions remain.

Final gate: **208 tests**, zero failures/errors/skips. Lint still exits1 with the
two existing `RollingFileDiagnosticLogger.kt:14` NewApi errors and 20 warnings.
`git diff --check` passes. This turn changed only the adapter test file and the two
review reports. All B changes remain uncommitted; no version/push/device action.

David retest for evidence:

1. Use the build made from this workspace; retain auth/bindings and select Kpop + Custom30. Record build provenance and test time.
2. With a currently saved bound output, Generate immediately. Expect same-ID reuse, no naming. If permission fails, export diagnostics immediately before another attempt.
3. Record the failing event's operation name, HTTP status, failure category, and exception class. Membership failure should say `managed output library membership`, HTTP403, PERMISSION. Do not share tokens.
4. If reuse succeeds, unfollow the same ID and Generate immediately. Expect replacement naming; Cancel and verify no writes. Generate again and accept the default; verify new-ID creation/population.
5. Immediately Generate again expecting same new-ID reuse. Export diagnostics immediately on failure. Retry after about ten minutes without state changes and capture that outcome/time too.
6. Stop with the diagnostic evidence; permission errors must not trigger replacement or weaken the resolver. A repeat of the same UI message alone cannot localize the failing API operation.

The original repair record below describes the preceding 204-test validation.

## 1. Root cause and evidence limits

`CreateOutputPlaylist.resolveOutput` returned a listing hit immediately, or, after
exhausting pagination, returned an exact-ID GET success immediately. Both results
selected UPDATED, followed by the destructive empty-URI PUT clear. Only direct
GET 404 could select replacement naming. No library-membership evidence was used.

Spotify's documented "delete" is unfollow even for the owner. The underlying
playlist survives; direct readability and retained `owner.id` do not establish
that it remains in the owner's library. This is the concrete resolver defect.
The former tests explicitly required reuse for listing miss / GET success.

The UI message proves the physical attempt reached UPDATED and attempted clear.
It does **not** establish whether the resolver used a stale listing hit or GET 200,
nor whether clear returned 403, 404, timeout, or another failure. No failed-run
HTTP diagnostics were supplied. Those exact physical responses remain unconfirmed;
this report does not invent them. Both resolver entry paths are now protected.

References checked October 1, 2026:

- [Spotify playlist deletion/unfollow semantics](https://developer.spotify.com/documentation/web-api/concepts/playlists)
- [GET playlist metadata](https://developer.spotify.com/documentation/web-api/reference/get-playlist): owner/collaborative fields exist, but no writable capability or current-user membership field. The adapter retains ID/name; adding owner alone would not solve unfollow.
- [Check User's Saved Items](https://developer.spotify.com/documentation/web-api/reference/check-library-contains): GET `/me/library/contains`, playlist URI input, Boolean array response, playlist-read-private scope already requested by this app.
- [Current-user playlist-follow check](https://developer.spotify.com/documentation/web-api/reference/check-if-user-follows-playlist) is deprecated in favor of the library endpoint; the fix uses the current endpoint.
- [Replace/clear items](https://developer.spotify.com/documentation/web-api/reference/reorder-or-replace-playlists-items): PUT `/playlists/{id}/items` with empty `uris` is the documented clear request. No mutation is used as an access probe.

## 2. Why tests missed it

The lifecycle fake removed the playlist object entirely and synthesized GET 404
for deletion. Its GET-success test encoded the wrong assumption that readability
was sufficient for reuse. Neither the API abstraction nor fake represented saved
membership. An unfollowed, readable, still-owned object was never exercised.

## 3. Files changed for this repair

- `app/src/main/java/com/royalshuffle/android/output/CreateOutputPlaylist.kt`: read-only membership gate on listing hits and successful direct lookup.
- `app/src/main/java/com/royalshuffle/android/output/OutputContracts.kt`: required `isPlaylistSaved` API contract, without a permissive production default.
- `app/src/main/java/com/royalshuffle/android/data/remote/SpotifyOutputPlaylistApi.kt`: exact encoded playlist-URI membership request and strict single Boolean parsing.
- `app/src/main/java/com/royalshuffle/android/data/remote/SpotifyWebApiClient.kt`: JSON array response support sharing the existing transport/status/retry/auth/error machinery.
- `app/src/test/java/com/royalshuffle/android/output/ManagedOutputLifecycleTest.kt`: realistic adapter-backed regression, stale listing, error, guard, and clear-failure cases.
- `app/src/test/java/com/royalshuffle/android/output/TestOutputRegistry.kt`: explicitly saved membership for existing unbound test fakes.
- `app/src/test/java/com/royalshuffle/android/data/remote/SpotifyOutputPlaylistApiTest.kt`: exact request, strict responses, HTTP status tests.
- `README.md`, `CATCH_UP_B_REVIEW.md`, this report: corrected behavior and validation record.

## 4. Corrected resolution and recovery

Search normal listing by exact bound ID with existing pagination checks. On a
miss, directly GET that exact ID. Only that GET's 404 establishes missing identity.
After either a listing hit or successful exact GET, check
`GET /v1/me/library/contains?uris=spotify%3Aplaylist%3A<ID>`.

- `[true]`: retain ID/name and reuse through the existing clear/append path.
- `[false]`: stale/unfollowed; enter creation-only naming with the source/session default.
- Error, malformed response, wrong cardinality, or non-Boolean: abort without naming or writes; keep binding. A membership-endpoint 404 is not a playlist lookup 404.

Cancel/blank preserve the old binding and issue no remote writes. Accepted naming
checks the expected old ID before create, checks again when committing the new
binding plus exclusion, and only then populates the new ID. Old exclusion remains.
No clear of the stale ID, no automatic re-follow, and no implicit adoption occurs.

The HTTP abstraction already preserved status in `SpotifyWebApiException` and
the partial-write cause. It was not erasing 404. Clear 403/404 remains a failed
write, never automatic replacement; connectivity/server/malformed acknowledgement
protection and explicit-429-only retry policy remain intact.

## 5. Seven added regression tests

1. Real API adapter: Kpop + Custom30, listing miss, GET200 retaining owner/private metadata, membership `[false]`; cancel and blank retain old association with GETs only; accepted default creates/binds/populates replacement with two POSTs and zero PUTs.
2. Stale listing hit plus membership false enters naming and never clears the old ID.
3. Membership failures (including 404, 403, 401, malformed200, connectivity, server, quota) preserve binding and suppress writes for both listing/direct paths.
4. Readable/unfollowed replacement still rejects a concurrent expected-old binding change before creation.
5. Clear403/404 after successful resolution remains partial-write failure with original status, one clear, no append/replacement/prompt.
6. Membership checks exact URI and both Boolean outcomes; rejects empty, multiple, string, null, object, malformed responses with INVALID_RESPONSE and status200.
7. Membership and clear retain 403/404 status and never replay.

Existing ID reuse/external rename, preset60 == Custom60, Artist Separation identity
exclusion, Custom30 isolation, legacy exclusion-only migration, creation naming,
binding guards, and all Catch-Up A regression tests remain in the full suite.

## 6. Automated validation

- `testDebugUnitTest --offline`: 204 tests; zero failures, errors, or skips.
- `lintDebug --offline`: exit1, two known pre-existing NewApi errors at
  `diagnostics/RollingFileDiagnosticLogger.kt:14` (`Instant.now()` on minSDK23;
  API26 required), 20 warnings. No new lint errors; no suppression/baseline added.
- `git diff --check`: clean.

## 7. Remaining Spotify/API ambiguity

Library membership establishes saved/followed state, not a guarantee of write
permission. Ownership also is not a writable capability. Permissions, membership,
or connectivity can change between resolution and clear. Such failures still
stop with the existing ambiguous-write protection. No read can make the subsequent
remote mutation atomic with Spotify library state.

The new endpoint's actual behavior for this app/token must be confirmed on
David/Aurora's physical retest. Existing playlist-read-private authorization is
used; no scope changes. API rejection fails closed. No live Spotify call was made
by this repair. The original failed clear's HTTP status requires the original
diagnostic log if available; it cannot be recovered from the quoted UI alone.

## 8. Exact David/Aurora physical retest

1. Retain app/authentication state and Kpop + Custom30. If the old stale binding still exists, start at step3. Otherwise Generate, accept `RND30M-Kpop`, verify population, and record the resulting Spotify ID/link.
2. In Spotify delete/unfollow that output. Do not change source, Custom30, or registry; verify it disappears from the library.
3. Generate. Expect creation-only prompt with selected default `RND30M-Kpop`; capture diagnostics if any error occurs. Verify no new playlist or old-ID item writes before consent.
4. Cancel. Verify no new playlist and old binding remains. Generate again; expect the same prompt.
5. Submit blank/whitespace. Verify rejection, no new playlist/writes, old binding remains. Generate again; expect the same default.
6. Accept default. Verify exactly one replacement with a different ID is created/populated, binding switches to new ID, and old ID is never cleared or re-followed.
7. Generate again unchanged: verify new ID reused without naming, no duplicate playlist.
8. Rename replacement externally; Generate again: verify same ID and external name retained. Toggle Artist Separation: verify same Custom30 binding.
9. Check preset60 then Custom60 reuse their shared separate ID, and Full remains separate; return to Custom30 and verify replacement ID reuse.
10. Preserve diagnostics from any failure, especially operation name, HTTP status,
    category, and exception class for `managed output lookup`,
    `managed output library membership`, and `managed output clear`.

## 9. Final git state

HEAD remains the accepted A checkpoint `0b06867beac125a456fc71e55f007e90780c75db`
on `android/main`. Index empty; all B and repair changes uncommitted. Exact short
status is recorded in the final section of `CATCH_UP_B_REVIEW.md`, now including
the shared Web API client and this report. Existing Gradle/wrapper/settings and
research changes remain untouched. No version bump, push, installation, or
Elijah-device action. Stop after automated validation for David/Aurora retest.
