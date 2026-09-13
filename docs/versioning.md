# Versioning and releases

RoyalShuffle currently maintains independent Windows, Linux, and Android
versions. A platform version describes that platform's deliverable; it is not
automatically the version of every implementation in the repository.

## Existing releases

The following published tag names are permanent and remain valid:

| Platform | Release | Tag |
| --- | --- | --- |
| Windows | 0.4.2 stable | `windows/v0.4.2` |
| Linux | 0.5.0 RC1 | `v0.5.0-rc.1` |
| Linux | 0.5.0 RC2 | `v0.5.0-rc.2` |

The unprefixed Linux RC tags are legacy names. They must not be renamed or
recreated because published releases and external links depend on them.

## Future tags

New platform-specific releases use `<platform>/v<version>`:

```text
windows/v0.4.3
linux/v0.5.0
android/v0.3.0
```

Prereleases keep the same platform prefix:

```text
linux/v0.5.0-rc.3
android/v0.3.0-beta.1
```

Use an unprefixed `vX.Y.Z` in the future only if RoyalShuffle deliberately
adopts one unified cross-platform version.

## Branches versus tags

- `main` is the moving canonical branch for shared Python/Core, Windows GUI,
  Linux CLI, CI, packaging definitions, and repository documentation.
- Android remains on its separate development line for now.
- A supported release family may use a maintenance branch when it needs
  backports.
- An exact published release is always identified by an immutable annotated
  tag, not merely by a branch name.

Release notes and downloadable artifacts belong to the GitHub Release attached
to the corresponding tag.
