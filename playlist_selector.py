def select_playlist(playlists):
    for number, playlist in enumerate(playlists, start=1):
        print(
            f"{number:2}. "
            f"{playlist['name']}"
        )

    print()

    while True:
        choice = input("Choose a playlist number: ").strip()

        try:
            playlist_index = int(choice) - 1

            if playlist_index < 0:
                raise IndexError

            return playlists[playlist_index]

        except (ValueError, IndexError):
            print("Please enter a valid playlist number.")