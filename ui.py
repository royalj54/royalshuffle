import tkinter as tk

from auth import authenticate


def connect_spotify():
    authenticate()


def main():
    root = tk.Tk()
    root.title("RoyalShuffle")
    root.geometry("500x300")

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

    connect_button = tk.Button(
        root,
        text="Connect Spotify",
        command=connect_spotify
    )
    connect_button.pack(pady=20)

    root.mainloop()


if __name__ == "__main__":
    main()