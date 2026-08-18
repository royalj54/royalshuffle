import tkinter as tk

from auth import start_authentication, finish_authentication


def connect_spotify(
    status_label, 
    callback_entry, 
    connect_button,
    complete_button,
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
    root.geometry("650x450")

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

    root.mainloop()


if __name__ == "__main__":
    main()