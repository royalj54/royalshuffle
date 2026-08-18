import tkinter as tk


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
    subtitle.pack()

    root.mainloop()


if __name__ == "__main__":
    main()