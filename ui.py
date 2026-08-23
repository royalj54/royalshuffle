import tkinter as tk
import threading

from auth import (
    start_authentication, 
    finish_authentication,
    wait_for_spotify_callback,
    save_token_data,
    load_token_data,
    refresh_access_token,
    clear_token_data,
)

from spotify_client import SpotifyClient
from royalshuffle import royal_shuffle

def load_spotify_session(
    access_token,
    status_label,
    connect_button,
    playlist_listbox,
    eligible_playlists,
    client_state,
):
    client = SpotifyClient(access_token)
    client_state["client"] = client

    playlists = client.get_playlists()

    eligible_playlists.clear()
    eligible_playlists.extend([
        playlist
        for playlist in playlists
        if not playlist["name"].endswith(
            " - RANDOM"
        )
    ])

    playlist_listbox.config(state="normal")
    playlist_listbox.delete(0, tk.END)

    for playlist in eligible_playlists:
        playlist_listbox.insert(
            tk.END,
            playlist["name"],
        )

    status_label.config(
        text="Connected to Spotify • Select a playlist"
    )

    connect_button.config(state="disabled")

def connect_spotify(
    status_label,
    connect_button,
    playlist_listbox,
    eligible_playlists,
    client_state,
):
    callback_state = {
        "url": None,
        "error": None,
    }

    def listen_for_callback():
        try:
            callback_state["url"] = wait_for_spotify_callback()
        except Exception as exc:
            callback_state["error"] = exc

    listener_thread = threading.Thread(
        target=listen_for_callback,
        daemon=True,
    )
    listener_thread.start()

    auth_session = start_authentication()

    status_label.config(
        text="Waiting for Spotify authorization..."
    )

    connect_button.config(state="disabled")

    def finish_connection(callback_url):
        print("DEBUG: finishing authentication")

        try:
            token_data = finish_authentication(
                callback_url,
                auth_session["code_verifier"],
                auth_session["state"],
            )

            save_token_data(token_data)

            access_token = token_data["access_token"]

            load_spotify_session(
                access_token,
                status_label,
                connect_button,
                playlist_listbox,
                eligible_playlists,
                client_state,
            )

        except Exception as exc:
            print(
                f"Spotify automatic login failed: {exc!r}"
            )

            clear_token_data()

            status_label.config(
                text="Not connected"
            )

            connect_button.config(
                state="normal"
            )

    def check_for_callback():
        if callback_state["error"] is not None:
            print(
                "Spotify callback listener failed: "
                f'{callback_state["error"]!r}'
            )

            status_label.config(
                text="Spotify connection failed. Click Connect Spotify to retry."
            )

            connect_button.config(state="normal")
            return

        if callback_state["url"] is not None:
            finish_connection(
                callback_state["url"]
            )
            return

        status_label.after(
            100,
            check_for_callback,
        )

    check_for_callback()

def main():
    root = tk.Tk()
    root.title("RoyalShuffle")
    root.geometry()
    root.minsize(500, 500)

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

    connect_button = tk.Button(
        root,
        text="Connect Spotify",
        width=20,
        height=2,
        font=("Arial", 11),
        command=lambda: connect_spotify(
            status_label,
            connect_button,
            playlist_listbox,
            eligible_playlists,
            client_state,
        )
    )
    connect_button.pack(pady=20)

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
        state="disabled",
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
                    f'{result["action"].title()}: '
                    f'{result["name"]} '
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

    def try_saved_spotify_session():
        saved_token_data = load_token_data()

        if not saved_token_data:
            return

        refresh_token = saved_token_data.get(
            "refresh_token"
        )

        if not refresh_token:
            return

        status_label.config(
            text="Reconnecting to Spotify..."
        )

        try:
            refreshed_token_data = refresh_access_token(
                refresh_token
            )

            saved_token_data.update(
                refreshed_token_data
            )

            save_token_data(saved_token_data)

            access_token = saved_token_data[
                "access_token"
            ]

            load_spotify_session(
                access_token,
                status_label,
                connect_button,
                playlist_listbox,
                eligible_playlists,
                client_state,
            )

        except Exception as exc:
            print(
                f"Spotify automatic login failed: {exc!r}"
            )

            status_label.config(
                text="Not connected"
            )

            connect_button.config(
                state="normal"
            )

    root.after(
        100,
        try_saved_spotify_session,
    )

    root.mainloop()

if __name__ == "__main__":
    main()