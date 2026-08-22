import tkinter as tk

from auth import start_authentication, finish_authentication
from spotify_client import SpotifyClient
from royalshuffle import royal_shuffle

def connect_spotify(
    status_label, 
    callback_entry, 
    connect_button,
    complete_button,
    playlist_listbox,
    eligible_playlists,
    client_state,
):
    auth_session = start_authentication()

    status_label.config(
        text="Waiting for Spotify callback..."
    )

    callback_entry.config(state="normal")
    connect_button.config(state="disabled")

    def finish_connection():
        callback_url = callback_entry.get().strip()

        if not callback_url:
            status_label.config(
                text="Paste the callback URL first"
            )
            return

        print("DEBUG: finishing authentication")

        access_token = finish_authentication(
            callback_url,
            auth_session["code_verifier"],
            auth_session["state"],
        )

        print(
            f"DEBUG: token received: {bool(access_token)}"
        )

        if access_token:
            status_label.config(
                text="Connected to Spotify"
            )
            callback_entry.config(state="disabled")
            complete_button.config(state="disabled")

            client = SpotifyClient(access_token)
            client_state["client"] = client

            playlists = client.get_playlists()

            eligible_playlists.clear()
            eligible_playlists.extend([
                playlist
                for playlist in playlists
                if not playlist["name"].endswith(" - RANDOM")
            ])

            for playlist in eligible_playlists:
                playlist_listbox.insert(tk.END, playlist["name"])

    complete_button.config(
        command=finish_connection,
        state="normal",
    )

    callback_entry.bind(
        "<Return>",
        lambda event: finish_connection()
    )

def main():
    root = tk.Tk()
    root.title("RoyalShuffle")
    root.minsize(650, 700)

    title = tk.Label(
        root,
        text="RoyalShuffle",
        font=("Arial", 24)
    )
    title.pack(pady=40)

    subtitle = tk.Label(
        root,
        text="Transparent, user-controlled Spotify shuffle"
    )
    subtitle.pack(pady=10)
    status_label = tk.Label(
        root,
        text="Not connected"
    )
    status_label.pack(pady=10)

    callback_entry = tk.Entry(
        root,
        width=60,
        state="disabled"
    )
    callback_entry.pack(pady=10)

    connect_button = tk.Button(
        root,
        text="Connect Spotify",
        width=20,
        height=2,
        font=("Arial", 11),
        command=lambda: connect_spotify(
            status_label,
            callback_entry,
            connect_button,
            complete_button,
            playlist_listbox,
            eligible_playlists,
            client_state,
        )
    )
    connect_button.pack(pady=20)

    complete_button = tk.Button(
        root,
        text="Complete Connection",
        width=20,
        state="disabled",
    )
    complete_button.pack(pady=10)

    playlist_frame = tk.Frame(root)
    playlist_frame.pack(
        fill="both",
        expand=True,
        padx=20,
        pady=10,
    )

    playlist_scrollbar = tk.Scrollbar(
        playlist_frame,
        orient="vertical",
    )

    playlist_listbox = tk.Listbox(
        playlist_frame,
        width=60,
        height=8,
        yscrollcommand=playlist_scrollbar.set,
    )

    playlist_scrollbar.config(
        command=playlist_listbox.yview
    )

    playlist_scrollbar.pack(
        side="right",
        fill="y",
    )

    playlist_listbox.pack(
        side="left",
        fill="both",
        expand=True,
    )



    eligible_playlists = []

    selected_playlist_state = {
        "playlist": None
    }

    client_state = {
        "client": None
    }

    def update_status(message):
        print(f"status: {message}")
        
        status_label.config(
            text=message
        )

        root.update_idletasks()


    def handle_playlist_selection():
        selection = playlist_listbox.curselection()

        if not selection:
            status_label.config(
                text="Select a playlist first"
            )
            return

        selected_playlist = eligible_playlists[selection[0]]
        selected_playlist_state["playlist"] = selected_playlist

        status_label.config(
            text=f'Selected: {selected_playlist["name"]}'
        )

        royal_shuffle_button.config(
            state="normal"
        )

        print(
            f'DEBUG: selected playlist ID: {selected_playlist["id"]}'
        )

    playlist_listbox.bind(
        "<Double-Button-1>",
        lambda event: handle_playlist_selection(),
    )

    playlist_listbox.bind(
        "<Button-1>",
        lambda event: playlist_listbox.focus_set(),
    )

    playlist_listbox.bind(
        "<Return>",
        lambda event: handle_playlist_selection(),
    )

    playlist_listbox.bind(
        "<KP_Enter>",
        lambda event: handle_playlist_selection(),
    )

    select_button = tk.Button(
        root,
        text="Select Playlist",
        width=20,
        command=handle_playlist_selection,
    )
    select_button.pack(pady=10)

    def handle_royal_shuffle():
        selected_playlist = selected_playlist_state["playlist"]

        if not selected_playlist:
            status_label.config(
                text="Select a playlist first"
            )
            return

        royal_shuffle_button.config(
            state="disabled"
        )

        status_label.config(
            text=f'Royal Shuffling: {selected_playlist["name"]}...'
        )
        root.update_idletasks()

        try:
            result = royal_shuffle(
                client_state["client"],
                selected_playlist,
                status_callback=update_status,
            )

            status_label.config(
                text=(
                    f'Created {result["name"]} '
                    f' • {result["item_count"]} items'
                )
            )

        except Exception as exc:
            print(
                f"Royal Shuffle failed: {exc!r}"
            )

            status_label.config(
                text="Royal Shuffle failed. Please try again."
            )

        finally:
            royal_shuffle_button.config(
                state='normal'
            )

    royal_shuffle_button = tk.Button(
        root,
        text="Royal Shuffle",
        width=20,
        state="disabled",
        command=handle_royal_shuffle,
    )
    royal_shuffle_button.pack(pady=10)


    root.mainloop()

if __name__ == "__main__":
    main()