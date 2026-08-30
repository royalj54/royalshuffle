import time

import requests

from auth import log_debug


PACING_OPERATION_INTERVAL = 20
PACING_PAUSE_SECONDS = 1
# Used only when Spotify rate-limits a request without a usable delay.
RATE_LIMIT_FALLBACK_SECONDS = 1
MAX_RATE_LIMIT_RETRIES = 3


class SpotifyClient:
    def __init__(self, access_token):
        self.headers = {
            "Authorization": f"Bearer {access_token}"
        }

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

            if retry_count >= MAX_RATE_LIMIT_RETRIES:
                log_debug(
                    f"Spotify {operation} returned HTTP 429; "
                    f"Retry-After={retry_after!r}; retries_exhausted"
                )
                break

            retry_count += 1
            log_debug(
                f"Spotify {operation} returned HTTP 429; "
                f"Retry-After={retry_after!r}; "
                f"retry={retry_count}/{MAX_RATE_LIMIT_RETRIES}"
            )

            try:
                delay = max(float(retry_after), 0)
            except (TypeError, ValueError):
                delay = RATE_LIMIT_FALLBACK_SECONDS

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

    def _pace_bulk_operation(self, operation_count, has_more):
        if (
            has_more
            and operation_count % PACING_OPERATION_INTERVAL == 0
        ):
            log_debug(
                "Pacing bulk Spotify playlist operation; "
                f"operations_completed={operation_count}; "
                f"pause_seconds={PACING_PAUSE_SECONDS}"
            )
            time.sleep(PACING_PAUSE_SECONDS)

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
                })

            url = data.get("next")
            params = {}

            self._pace_bulk_operation(
                page_number,
                has_more=bool(url),
            )

        log_debug(
            "Completed Spotify playlist item fetch; "
            f"items={len(items)}; pages={page_number}"
        )

        return items

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

    def add_playlist_items(self, playlist_id, uris):
        total_batches = (len(uris) + 99) // 100
        items_written = 0

        for batch_number, start in enumerate(
            range(0, len(uris), 100),
            start=1,
        ):
            batch = uris[start:start + 100]

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
                raise

            items_written += len(batch)

            self._pace_bulk_operation(
                batch_number,
                has_more=batch_number < total_batches,
            )

        log_debug(
            "Completed Spotify playlist population; "
            f"batches={total_batches}; items_written={items_written}"
        )
