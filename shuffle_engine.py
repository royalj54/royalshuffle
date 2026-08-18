import random


def shuffle_items(items):
    shuffled = items.copy()

    rng = random.SystemRandom()
    rng.shuffle(shuffled)

    return shuffled
