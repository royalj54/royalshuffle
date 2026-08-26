import csv


COLUMNS = [
    "Playlist Position",
    "Track Name",
    "Artists",
    "Album",
    "Duration",
    "Spotify URL",
    "Spotify URI",
    "Date Added",
    "Added By",
    "Disc Number",
    "Track Number",
    "Explicit",
]


def format_duration(duration_ms):
    if duration_ms is None:
        return ""

    total_seconds = max(0, int(duration_ms) // 1000)
    hours, remainder = divmod(total_seconds, 3600)
    minutes, seconds = divmod(remainder, 60)

    if hours:
        return f"{hours}:{minutes:02}:{seconds:02}"

    return f"{minutes}:{seconds:02}"


def formula_safe(value):
    if value is None:
        return ""

    text = str(value)
    if text.startswith(("=", "+", "-", "@")):
        return "'" + text

    return text


def export_playlist_csv(path, items):
    with open(path, "w", newline="", encoding="utf-8-sig") as csv_file:
        writer = csv.DictWriter(csv_file, fieldnames=COLUMNS)
        writer.writeheader()

        for item in items:
            row = {
                "Playlist Position": item.get("playlist_position"),
                "Track Name": item.get("name", ""),
                "Artists": item.get("artists", ""),
                "Album": item.get("album", ""),
                "Duration": format_duration(item.get("duration_ms")),
                "Spotify URL": item.get("spotify_url", ""),
                "Spotify URI": item.get("uri", ""),
                "Date Added": item.get("date_added", ""),
                "Added By": item.get("added_by", ""),
                "Disc Number": item.get("disc_number"),
                "Track Number": item.get("track_number"),
                "Explicit": "Yes" if item.get("explicit", False) else "No",
            }
            writer.writerow({
                key: formula_safe(value)
                for key, value in row.items()
            })

    return len(items)
