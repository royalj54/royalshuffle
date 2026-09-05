# Native Linux acceptance checklist

Run this checklist on a real Linux installation before approving RoyalShuffle
0.5.0rc2. WSL validation is useful but does not satisfy this gate. No source
checkout or project-development knowledge is required.

Use a Spotify account that is allowlisted as a user of the RoyalShuffle Spotify
Developer app. Before starting, create a small disposable source playlist that
contains several tracks and at least one duplicate. Record its Spotify playlist
ID; using an ID avoids ambiguity between playlists with the same name.

## 1. Install the published wheel

Python 3.10 or 3.12, pip, and the Python `venv` module are required. On a
distribution that separates the venv module, install its corresponding package
first (for example, `python3-venv`).

```bash
python3 --version
python3 -m venv ~/royalshuffle-0.5.0rc2-test
source ~/royalshuffle-0.5.0rc2-test/bin/activate
python -m pip install --upgrade pip
python -m pip install "https://github.com/royalj54/royalshuffle/releases/download/v0.5.0-rc.2/royalshuffle-0.5.0rc2-py3-none-any.whl"
royalshuffle --version
```

- [ ] Installation completes without an error.
- [ ] `royalshuffle --version` reports `RoyalShuffle 0.5.0rc2`.

## 2. Check diagnostics before authentication

```bash
royalshuffle diagnostics
test ! -e ~/.config/royalshuffle/token.json
```

- [ ] Platform and paths describe the native Linux system, not WSL or Windows.
- [ ] `Saved session exists` is `no`.
- [ ] The second command succeeds, confirming diagnostics did not create a
      token.

If XDG variables were already set, use the `Token path` printed by diagnostics
instead of the default path in the `test` command.

## 3. Test Spotify authentication

Run the command in an interactive terminal:

```bash
royalshuffle auth
```

First test the localhost flow. Let RoyalShuffle open the browser, or manually
open the printed authorization URL. Authorize the app and press Enter in the
terminal to wait for the callback at `http://127.0.0.1:8888/callback`.

- [ ] The automatic browser attempt works, or the printed URL works when opened
      manually.
- [ ] The localhost callback completes and the terminal reports
      `Spotify authentication saved successfully.`

Confirm the token path and owner-only permissions:

```bash
royalshuffle diagnostics
stat -c '%a %n' ~/.config/royalshuffle/token.json
```

- [ ] Diagnostics reports `Saved session exists: yes`.
- [ ] `stat` reports mode `600` for the token. If diagnostics prints a different
      token path because of `XDG_CONFIG_HOME`, inspect that path instead.

Test the manual fallback without deleting the working token. Back it up, run
authentication again, and paste the complete callback URL from the browser into
the terminal prompt instead of pressing Enter:

```bash
cp ~/.config/royalshuffle/token.json ~/.config/royalshuffle/token.json.acceptance-backup
royalshuffle auth
```

- [ ] Pasting the complete callback URL also reports successful authentication.

If the fallback fails, restore the previously working token before continuing:

```bash
mv ~/.config/royalshuffle/token.json.acceptance-backup ~/.config/royalshuffle/token.json
```

If it succeeds, remove the backup:

```bash
rm ~/.config/royalshuffle/token.json.acceptance-backup
```

Use the token path printed by diagnostics if XDG configuration changes the
default location.

## 4. Confirm saved-session reuse and playlist discovery

Start a new shell, reactivate the environment, and list playlists:

```bash
source ~/royalshuffle-0.5.0rc2-test/bin/activate
royalshuffle playlists
```

- [ ] No new authentication prompt appears.
- [ ] Every eligible source playlist expected for the account is listed as
      `<name><TAB><Spotify playlist ID>`, including accounts with enough
      playlists to require multiple Spotify API pages.

## 5. Test shuffle and managed-output filtering

Replace `SPOTIFY_PLAYLIST_ID` with the disposable source playlist ID:

```bash
royalshuffle shuffle "SPOTIFY_PLAYLIST_ID"
royalshuffle playlists
```

- [ ] Shuffle reports completion and an output playlist ID.
- [ ] Spotify contains the managed output named with the `- RANDOM` suffix.
- [ ] The output contains the expected source tracks in randomized order.
- [ ] The managed output is not included by the second `playlists` command as a
      possible shuffle source.

## 6. Test CSV export and import

```bash
royalshuffle export "SPOTIFY_PLAYLIST_ID" --output "$PWD/royalshuffle-rc2-test.csv"
royalshuffle import "$PWD/royalshuffle-rc2-test.csv" --name "RoyalShuffle RC2 Import Test"
```

- [ ] Export reports completion, its row count, and the requested path.
- [ ] The CSV preserves the disposable playlist's current order and metadata.
- [ ] Import reports progress, completion, track count, and an output playlist
      ID.
- [ ] The imported Spotify playlist preserves exact CSV row order and duplicate
      entries.

## 7. Test XDG overrides

Use an isolated temporary XDG tree and authenticate again because it has a
separate token location:

```bash
XDG_TEST_ROOT="$(mktemp -d)"
export XDG_CONFIG_HOME="$XDG_TEST_ROOT/config"
export XDG_STATE_HOME="$XDG_TEST_ROOT/state"
export XDG_DATA_HOME="$XDG_TEST_ROOT/data"
royalshuffle diagnostics
royalshuffle auth
royalshuffle playlists
royalshuffle export "SPOTIFY_PLAYLIST_ID"
find "$XDG_TEST_ROOT" -maxdepth 4 -type f -print
```

- [ ] Diagnostics reports paths below the temporary root.
- [ ] Authentication creates
      `$XDG_CONFIG_HOME/royalshuffle/token.json` with mode `600`.
- [ ] State and diagnostics are below `$XDG_STATE_HOME/royalshuffle`.
- [ ] The default CSV export is below
      `$XDG_DATA_HOME/royalshuffle/Exports`.

Remove only the temporary test tree created above, then restore the original
environment:

```bash
rm -rf -- "$XDG_TEST_ROOT"
unset XDG_CONFIG_HOME XDG_STATE_HOME XDG_DATA_HOME XDG_TEST_ROOT
```

## 8. Test access-token refresh

RoyalShuffle refreshes the saved access token before creating a new Spotify
client. The following procedure safely replaces only the saved access token
with a known invalid value while preserving the refresh token. Back up the
token first and do not print or share either file:

```bash
TOKEN_PATH="${XDG_CONFIG_HOME:-$HOME/.config}/royalshuffle/token.json"
cp "$TOKEN_PATH" "$TOKEN_PATH.refresh-test-backup"
python - "$TOKEN_PATH" <<'PY'
import json
import os
import sys

path = sys.argv[1]
with open(path, encoding="utf-8") as token_file:
    token = json.load(token_file)
token["access_token"] = "expired-access-token-acceptance-test"
with open(path, "w", encoding="utf-8") as token_file:
    json.dump(token, token_file, indent=2)
    token_file.write("\n")
os.chmod(path, 0o600)
PY
royalshuffle playlists
python - "$TOKEN_PATH" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as token_file:
    token = json.load(token_file)
if token.get("access_token") == "expired-access-token-acceptance-test":
    raise SystemExit("FAIL: saved access token was not refreshed")
print("PASS: refreshed access token was persisted")
PY
```

- [ ] `royalshuffle playlists` succeeds despite the deliberately invalid saved
      access token.
- [ ] The verification script reports that a refreshed access token was
      persisted.
- [ ] Starting another process and running `royalshuffle playlists` succeeds.

If any refresh step fails, restore the working backup:

```bash
mv "$TOKEN_PATH.refresh-test-backup" "$TOKEN_PATH"
```

If all refresh steps succeed, remove the backup and variable:

```bash
rm "$TOKEN_PATH.refresh-test-backup"
unset TOKEN_PATH
```

## 9. Cleanup and uninstall

In Spotify, manually remove the disposable source, managed `- RANDOM` output,
and `RoyalShuffle RC2 Import Test` playlists. Then remove the exported CSV and
uninstall the package:

```bash
rm -f -- "$PWD/royalshuffle-rc2-test.csv"
python -m pip uninstall royalshuffle
if command -v royalshuffle >/dev/null; then
    echo "FAIL: royalshuffle command is still installed"
else
    echo "PASS: royalshuffle command was removed"
fi
deactivate
```

- [ ] All temporary Spotify playlists and local CSV test output are removed.
- [ ] The console command is removed.
- [ ] Authentication, state, and diagnostics remain in their documented user
      directories, as expected; package uninstall does not delete user data.

## Report results

Send the completed checklist to the release coordinator. For every failure,
include:

- Linux distribution and version
- desktop/session type when browser behavior is relevant
- output of `python3 --version` and `royalshuffle --version`
- exact command, complete terminal error, and exit code (`echo $?` immediately
  after the failure)
- redacted `royalshuffle diagnostics` output
- whether OAuth used the localhost callback or pasted-URL fallback
- any partial or output Spotify playlist ID printed after a failed write
- the diagnostics log after reviewing it for information you do not want to
  share

The default diagnostics log is
`~/.local/state/royalshuffle/Diagnostics/royalshuffle_debug.log`. If XDG
variables are set, use the path printed by `royalshuffle diagnostics`. Unless
the release coordinator requests another channel, report issues at
<https://github.com/royalj54/royalshuffle/issues>.
