# Native Linux acceptance checklist

Run this checklist on a real Linux installation before approving a Linux
release candidate. WSL validation is useful but does not satisfy this gate.

- [ ] Clone a clean checkout, create a fresh virtual environment, and install
      from the built wheel.
- [ ] Confirm `royalshuffle --version` reports the intended release version.
- [ ] Run `royalshuffle diagnostics`; confirm Linux/XDG locations and that it
      does not create a token.
- [ ] Run `royalshuffle auth` in an interactive terminal.
- [ ] Confirm the browser-open attempt works, or use the printed URL manually.
- [ ] Complete authentication through the localhost callback.
- [ ] Repeat authentication using the pasted complete callback URL fallback.
- [ ] Confirm the persisted token file is readable only by its owner (mode
      `0600`).
- [ ] Start a fresh process and confirm the saved session is reused.
- [ ] Exercise an expired access token and confirm refresh persists and works in
      a later process.
- [ ] Confirm playlist discovery paginates and lists all eligible playlists.
- [ ] Confirm registered managed outputs are excluded as shuffle sources.
- [ ] Shuffle a small disposable playlist and verify the managed output.
- [ ] Export a disposable playlist and inspect its CSV order and metadata.
- [ ] Import that CSV and verify exact order and duplicate preservation.
- [ ] With XDG overrides set, confirm config, state, data, diagnostics, and
      exports are created in their documented locations.
- [ ] Remove temporary Spotify playlists and other remote test data manually.
- [ ] Uninstall RoyalShuffle and confirm the console command is removed while
      user data remains intact.
