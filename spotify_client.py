import requests


class SpotifyClient:
    def __init__(self, access_token):
        self.headers = {
            "Authorization": f"Bearer {access_token}"
        }

    def get_playlist_items(self, playlist_id):
        items = []

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

                items.append({
                    "uri": uri,
                    "name": item.get("name", "Unknown"),
                    "artists": artists,
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

    def find_playlist_by_name(self, playlist_name):
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
                    return playlist

            url = data.get("next")
            params = {}

        return None

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
