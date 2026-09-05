import math
import time

import requests

from auth import log_debug


# Used only when Spotify rate-limits a request without a usable delay.
RATE_LIMIT_FALLBACK_SECONDS = 1
MAX_AUTOMATIC_RETRY_AFTER_SECONDS = 60
MAX_RATE_LIMIT_RETRIES = 3
SPOTIFY_DEVELOPER_QUOTA_MESSAGE = (
    "Spotify developer quota exceeded. RoyalShuffle cannot make "
    "additional Spotify requests right now. Try again later."
)


class SpotifyRetryLaterError(Exception):
    pass


class SpotifyQuotaExceededError(SpotifyRetryLaterError):
    pass


class SpotifyTrackNotFoundError(Exception):
    def __init__(self, track_id):
        self.track_id = track_id
        super().__init__("Spotify track was not found")


class SpotifyClient:
    def __init__(self, access_token):
        self.headers = {
            "Authorization": f"Bearer {access_token}"
        }

    def _rate_limit_reason(self, response):
        try:
            payload = response.json()
        except (TypeError, ValueError):
            return None

        if not isinstance(payload, dict):
            return None

        error = payload.get("error")
        containers = [error, payload]

        for container in containers:
            if not isinstance(container, dict):
                continue

            for key in ("reason", "category"):
                value = container.get(key)

                if isinstance(value, str) and value.strip():
                    return value.strip().upper()

        return None

    def _retry_after_delay(self, retry_after):
        try:
            delay = float(retry_after)
        except (TypeError, ValueError):
            return RATE_LIMIT_FALLBACK_SECONDS

        if not math.isfinite(delay) or delay < 0:
            return RATE_LIMIT_FALLBACK_SECONDS

        return delay

    def _request(self, method, url, operation, **kwargs):
        retry_count = 0

        while True:
            try:
                response = getattr(requests, method)(
                    url,
                    **kwargs,
                )
            except Exception as exc:
                log_debug(
                    f"Spotify {operation} failed; "
                    f"exception_type={type(exc).__name__}"
                )
                raise

            if response.status_code != 429:
                break

            retry_after = response.headers.get("Retry-After")
            rate_limit_reason = self._rate_limit_reason(response)

            log_debug(
                f"Spotify {operation} returned HTTP 429; "
                f"reason={rate_limit_reason!r}; "
                f"Retry-After={retry_after!r}"
            )

            if rate_limit_reason == "QUOTA_EXCEEDED":
                raise SpotifyQuotaExceededError(
                    SPOTIFY_DEVELOPER_QUOTA_MESSAGE
                )

            delay = self._retry_after_delay(retry_after)

            if delay > MAX_AUTOMATIC_RETRY_AFTER_SECONDS:
                raise SpotifyRetryLaterError(
                    "Spotify asked RoyalShuffle to wait too long to "
                    "retry automatically. Please try again later."
                )

            if retry_count >= MAX_RATE_LIMIT_RETRIES:
                log_debug(
                    f"Spotify {operation} returned HTTP 429; "
                    f"Retry-After={retry_after!r}; retries_exhausted"
                )
                raise SpotifyRetryLaterError(
                    "Spotify is still limiting requests. "
                    "Please try again later."
                )

            retry_count += 1
            log_debug(
                f"Spotify {operation} returned HTTP 429; "
                f"Retry-After={retry_after!r}; "
                f"retry={retry_count}/{MAX_RATE_LIMIT_RETRIES}"
            )

            time.sleep(delay)

        try:
            response.raise_for_status()
        except Exception as exc:
            log_debug(
                f"Spotify {operation} failed; "
                f"http_status={response.status_code}; "
                f"Retry-After="
                f"{response.headers.get('Retry-After')!r}; "
                f"exception_type={type(exc).__name__}"
            )
            raise

        return response

    def get_playlist_items(self, playlist_id):
        items = []
        playlist_position = 0
        page_number = 0

        url = (
            f"https://api.spotify.com/v1/playlists/"
            f"{playlist_id}/items"
        )

        params = {
            "limit": 100
        }

        while url:
            response = self._request(
                "get",
                url,
                f"playlist item page {page_number + 1} fetch",
                headers=self.headers,
                params=params
            )

            data = response.json()
            page_number += 1
            total_items = data.get("total")

            log_debug(
                "Fetched Spotify playlist item page; "
                f"page={page_number}; "
                f"page_items={len(data['items'])}; "
                f"reported_total={total_items!r}"
            )

            for entry in data["items"]:
                playlist_position += 1
                item = entry.get("item")

                if not item:
                    continue

                uri = item.get("uri")

                if not uri:
                    continue

                artists = ", ".join(
                    artist["name"]
                    for artist in item.get("artists", [])
                )
                album = item.get("album") or {}
                added_by = entry.get("added_by") or {}
                external_urls = item.get("external_urls") or {}

                items.append({
                    "uri": uri,
                    "name": item.get("name", "Unknown"),
                    "artists": artists,
                    "playlist_position": playlist_position,
                    "album": album.get("name", ""),
                    "duration_ms": item.get("duration_ms"),
                    "spotify_url": external_urls.get("spotify", ""),
                    "date_added": entry.get("added_at", ""),
                    "added_by": added_by.get("id", ""),
                    "disc_number": item.get("disc_number"),
                    "track_number": item.get("track_number"),
                    "explicit": item.get("explicit", False),
                    "is_local": bool(
                        entry.get("is_local")
                        or item.get("is_local")
                        or uri.startswith("spotify:local:")
                    ),
                })

            url = data.get("next")
            params = {}

        log_debug(
            "Completed Spotify playlist item fetch; "
            f"items={len(items)}; pages={page_number}"
        )

        return items

    def get_track(self, track_id):
        try:
            response = self._request(
                "get",
                f"https://api.spotify.com/v1/tracks/{track_id}",
                "track catalog lookup",
                headers=self.headers,
            )
        except Exception as exc:
            response = getattr(exc, "response", None)
            if getattr(response, "status_code", None) == 404:
                raise SpotifyTrackNotFoundError(track_id) from exc
            raise

        return response.json()

    def get_playlists(self):
        playlists = []

        url = "https://api.spotify.com/v1/me/playlists"

        params = {
            "limit": 50
        }

        while url:
            response = self._request(
                "get",
                url,
                "playlist list page fetch",
                headers=self.headers,
                params=params
            )

            data = response.json()

            playlists.extend(data["items"])

            url = data.get("next")
            params = {}

        return playlists

    def find_playlists_by_name(self, playlist_name):
        matches = []
        url = "https://api.spotify.com/v1/me/playlists"

        params = {
            "limit": 50
        }

        while url:
            response = self._request(
                "get",
                url,
                "playlist name lookup page fetch",
                headers=self.headers,
                params=params
            )

            data = response.json()

            for playlist in data["items"]:
                if playlist["name"] == playlist_name:
                    matches.append(playlist)

            url = data.get("next")
            params = {}

        return matches

    def find_playlist_by_name(self, playlist_name):
        matches = self.find_playlists_by_name(playlist_name)
        return matches[0] if matches else None

    def create_playlist(self, name, description="", public=False):
        response = self._request(
            "post",
            "https://api.spotify.com/v1/me/playlists",
            "playlist creation",
            headers={
                **self.headers,
                "Content-Type": "application/json"
            },
            json={
                "name": name,
                "public": public,
                "description": description,
            },
        )

        return response.json()

    def clear_playlist(self, playlist_id):
        self._request(
            "put",
            (
                f"https://api.spotify.com/v1/playlists/"
                f"{playlist_id}/items"
            ),
            "playlist clearing",
            headers={
                **self.headers,
                "Content-Type": "application/json"
            },
            json={
                "uris": []
            },
        )

    def add_playlist_items(self, playlist_id, uris, progress_callback=None):
        total_batches = (len(uris) + 99) // 100
        items_written = 0

        for batch_number, start in enumerate(
            range(0, len(uris), 100),
            start=1,
        ):
            batch = uris[start:start + 100]

            if progress_callback:
                progress_callback(
                    batch_number,
                    total_batches,
                    start + 1,
                    start + len(batch),
                    len(uris),
                )

            log_debug(
                "Writing Spotify playlist item batch; "
                f"batch={batch_number}/{total_batches}; "
                f"batch_size={len(batch)}; "
                f"items_written={items_written}"
            )

            try:
                self._request(
                    "post",
                    (
                        f"https://api.spotify.com/v1/playlists/"
                        f"{playlist_id}/items"
                    ),
                    (
                        "playlist item batch write "
                        f"{batch_number}/{total_batches}"
                    ),
                    headers={
                        **self.headers,
                        "Content-Type": "application/json"
                    },
                    json={
                        "uris": batch
                    },
                )
            except Exception as exc:
                log_debug(
                    "Spotify playlist population failed; "
                    f"batch={batch_number}/{total_batches}; "
                    f"batch_size={len(batch)}; "
                    f"items_written={items_written}; "
                    f"exception_type={type(exc).__name__}"
                )
                exc.playlist_id = playlist_id
                exc.items_written = items_written
                exc.total_items = len(uris)
                raise

            items_written += len(batch)
            log_debug(
                "Confirmed Spotify playlist item batch; "
                f"batch={batch_number}/{total_batches}; "
                f"batch_size={len(batch)}; "
                f"items_written={items_written}"
            )

        log_debug(
            "Completed Spotify playlist population; "
            f"batches={total_batches}; items_written={items_written}"
        )

        return items_written
