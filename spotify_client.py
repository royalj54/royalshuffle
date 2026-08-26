import requests


class SpotifyClient:
    def __init__(self, access_token):
        self.headers = {
            "Authorization": f"Bearer {access_token}"
        }

    def get_playlist_items(self, playlist_id):
        items = []
        playlist_position = 0

        url = (
            f"https://api.spotify.com/v1/playlists/"
            f"{playlist_id}/items"
        )

        params = {
            "limit": 100
        }

        while url:
            response = requests.get(
                url,
                headers=self.headers,
                params=params
            )

            response.raise_for_status()
            data = response.json()

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

        return items

    def get_playlists(self):
        playlists = []

        url = "https://api.spotify.com/v1/me/playlists"

        params = {
            "limit": 50
        }

        while url:
            response = requests.get(
                url,
                headers=self.headers,
                params=params
            )

            response.raise_for_status()
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
            response = requests.get(
                url,
                headers=self.headers,
                params=params
            )

            response.raise_for_status()
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
        response = requests.post(
            "https://api.spotify.com/v1/me/playlists",
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

        response.raise_for_status()

        return response.json()

    def clear_playlist(self, playlist_id):
        response = requests.put(
            (
                f"https://api.spotify.com/v1/playlists/"
                f"{playlist_id}/items"
            ),
            headers={
                **self.headers,
                "Content-Type": "application/json"
            },
            json={
                "uris": []
            },
        )

        response.raise_for_status()

    def add_playlist_items(self, playlist_id, uris):
        for start in range(0, len(uris), 100):
            batch = uris[start:start + 100]

            response = requests.post(
                (
                    f"https://api.spotify.com/v1/playlists/"
                    f"{playlist_id}/items"
                ),
                headers={
                    **self.headers,
                    "Content-Type": "application/json"
                },
                json={
                    "uris": batch
                },
            )


            response.raise_for_status()
