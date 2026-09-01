import csv
import re
from dataclasses import dataclass


SPOTIFY_URI_COLUMN = "Spotify URI"
TRACK_URI_PATTERN = re.compile(
    r"^spotify:track:(?P<track_id>[A-Za-z0-9]{22})$"
)


@dataclass(frozen=True)
class PlaylistImportRow:
    line_number: int
    uri: str
    track_id: str


@dataclass(frozen=True)
class PlaylistImportValidationIssue:
    line_number: int | None
    code: str
    message: str


class PlaylistImportValidationError(ValueError):
    def __init__(self, issues):
        self.issues = tuple(issues)
        super().__init__("CSV playlist import validation failed")


def _row_is_blank(row):
    values = [*row.values()]
    return all(
        value is None
        or (
            isinstance(value, str)
            and not value.strip()
        )
        or (
            isinstance(value, list)
            and all(not str(item).strip() for item in value)
        )
        for value in values
    )


def _validate_uri(uri, line_number):
    if not uri:
        return None, PlaylistImportValidationIssue(
            line_number,
            "blank_uri",
            "Spotify URI is blank.",
        )

    if uri.startswith("spotify:local:"):
        return None, PlaylistImportValidationIssue(
            line_number,
            "local_uri",
            "Local Spotify files cannot be imported.",
        )

    parts = uri.split(":", 2)
    if len(parts) == 3 and parts[0] == "spotify" and parts[1] != "track":
        return None, PlaylistImportValidationIssue(
            line_number,
            "non_track_uri",
            f"Expected a track URI, found Spotify {parts[1]} content.",
        )

    match = TRACK_URI_PATTERN.fullmatch(uri)
    if not match:
        return None, PlaylistImportValidationIssue(
            line_number,
            "malformed_uri",
            "Spotify URI must be spotify:track:<22-character ID>.",
        )

    return match.group("track_id"), None


def parse_playlist_csv(path):
    issues = []
    rows = []

    try:
        with open(path, "r", newline="", encoding="utf-8-sig") as csv_file:
            reader = csv.DictReader(csv_file, strict=True)

            if reader.fieldnames is None:
                raise PlaylistImportValidationError([
                    PlaylistImportValidationIssue(
                        None,
                        "missing_header",
                        "CSV must contain a header row.",
                    )
                ])

            if SPOTIFY_URI_COLUMN not in reader.fieldnames:
                raise PlaylistImportValidationError([
                    PlaylistImportValidationIssue(
                        1,
                        "missing_spotify_uri_column",
                        f'CSV must contain the exact "{SPOTIFY_URI_COLUMN}" column.',
                    )
                ])

            for row in reader:
                line_number = reader.line_num
                if _row_is_blank(row):
                    continue

                uri = (row.get(SPOTIFY_URI_COLUMN) or "").strip()
                track_id, issue = _validate_uri(uri, line_number)
                if issue:
                    issues.append(issue)
                    continue

                rows.append(PlaylistImportRow(
                    line_number=line_number,
                    uri=uri,
                    track_id=track_id,
                ))
    except csv.Error as exc:
        issues.append(PlaylistImportValidationIssue(
            getattr(reader, "line_num", None),
            "malformed_csv",
            "CSV formatting is malformed.",
        ))

    if not rows and not issues:
        issues.append(PlaylistImportValidationIssue(
            None,
            "no_tracks",
            "CSV contains no track rows.",
        ))

    if issues:
        raise PlaylistImportValidationError(issues)

    return tuple(rows)
