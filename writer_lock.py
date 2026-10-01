"""Prompt, reentrant coordination on a stable file shared by all registry writers."""
from contextlib import contextmanager
from functools import wraps
from pathlib import Path
import os
import threading

from app_paths import state_folder

class WriterBusyError(RuntimeError):
    pass

_entries = {}
_entries_guard = threading.Lock()


@contextmanager
def writer_lock(directory=None):
    path = (Path(directory) if directory is not None else state_folder()) / ".royalshuffle_writer.lock"
    key = (os.getpid(), os.path.normcase(str(path.resolve())))
    with _entries_guard:
        entry = _entries.setdefault(key, {"lock": threading.RLock(), "depth": 0, "stream": None})
    if not entry["lock"].acquire(blocking=False):
        raise WriterBusyError("RoyalShuffle is busy in another writer. Try again later.")
    try:
        if entry["depth"] == 0:
            path.parent.mkdir(parents=True, exist_ok=True)
            stream = path.open("a+b")
            try:
                if path.stat().st_size == 0:
                    stream.write(b"\0")
                    stream.flush()
                stream.seek(0)
                if os.name == "nt":
                    import msvcrt
                    msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError as exc:
                stream.close()
                raise WriterBusyError("RoyalShuffle is busy in another process. Try again later.") from exc
            entry["stream"] = stream
        entry["depth"] += 1
        try:
            yield
        finally:
            entry["depth"] -= 1
            if entry["depth"] == 0:
                stream = entry["stream"]
                try:
                    stream.seek(0)
                    if os.name == "nt":
                        import msvcrt
                        msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
                    else:
                        import fcntl
                        fcntl.flock(stream.fileno(), fcntl.LOCK_UN)
                finally:
                    stream.close()
                    entry["stream"] = None
    finally:
        entry["lock"].release()


def state_writer(method):
    @wraps(method)
    def coordinated(self, *args, **kwargs):
        with writer_lock(self.path.parent):
            return method(self, *args, **kwargs)
    return coordinated
