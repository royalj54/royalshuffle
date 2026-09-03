import ctypes
import os
import uuid
from pathlib import Path


class GUID(ctypes.Structure):
    _fields_ = [
        ("Data1", ctypes.c_uint32),
        ("Data2", ctypes.c_uint16),
        ("Data3", ctypes.c_uint16),
        ("Data4", ctypes.c_ubyte * 8),
    ]

    @classmethod
    def from_string(cls, value):
        guid = uuid.UUID(value)
        return cls.from_buffer_copy(guid.bytes_le)


FOLDERID_DOCUMENTS = GUID.from_string(
    "FDD39AD0-238F-46AF-ADB4-6C85480369C7"
)
COINIT_APARTMENTTHREADED = 0x2
RPC_E_CHANGED_MODE = -2147417850


def _is_windows():
    return os.name == "nt"


def documents_folder():
    if not _is_windows():
        raise OSError("The Windows Documents folder is only available on Windows")

    shell32 = ctypes.WinDLL("shell32", use_last_error=True)
    ole32 = ctypes.WinDLL("ole32", use_last_error=True)

    shell32.SHGetKnownFolderPath.argtypes = [
        ctypes.POINTER(GUID),
        ctypes.c_uint32,
        ctypes.c_void_p,
        ctypes.POINTER(ctypes.c_wchar_p),
    ]
    shell32.SHGetKnownFolderPath.restype = ctypes.c_long
    ole32.CoTaskMemFree.argtypes = [ctypes.c_void_p]
    ole32.CoTaskMemFree.restype = None
    ole32.CoInitializeEx.argtypes = [ctypes.c_void_p, ctypes.c_uint32]
    ole32.CoInitializeEx.restype = ctypes.c_long
    ole32.CoUninitialize.argtypes = []
    ole32.CoUninitialize.restype = None

    initialization_result = ole32.CoInitializeEx(
        None,
        COINIT_APARTMENTTHREADED,
    )
    initialized_here = initialization_result in (0, 1)
    if not initialized_here and initialization_result != RPC_E_CHANGED_MODE:
        raise OSError(
            initialization_result,
            "Could not initialize Windows COM",
        )

    allocated_path = ctypes.c_wchar_p()
    try:
        result = shell32.SHGetKnownFolderPath(
            ctypes.byref(FOLDERID_DOCUMENTS),
            0,
            None,
            ctypes.byref(allocated_path),
        )
        if result != 0:
            raise OSError(result, "Could not resolve the Documents folder")
        if not allocated_path.value:
            raise OSError("Windows returned an empty Documents folder path")

        return Path(allocated_path.value)
    finally:
        if allocated_path:
            ole32.CoTaskMemFree(allocated_path)
        if initialized_here:
            ole32.CoUninitialize()


def _xdg_folder(environment_variable, default_relative_path):
    configured_path = os.environ.get(environment_variable)
    if configured_path:
        return Path(configured_path).expanduser() / "royalshuffle"

    return Path.home() / default_relative_path / "royalshuffle"


def config_folder():
    if _is_windows():
        return Path.home()

    return _xdg_folder("XDG_CONFIG_HOME", Path(".config"))


def state_folder():
    if _is_windows():
        return Path.home()

    return _xdg_folder("XDG_STATE_HOME", Path(".local") / "state")


def data_folder():
    if _is_windows():
        return documents_folder() / "RoyalShuffle"

    return _xdg_folder("XDG_DATA_HOME", Path(".local") / "share")


def token_file():
    if _is_windows():
        return Path.home() / ".royalshuffle_token.json"

    return config_folder() / "token.json"


def managed_playlists_file():
    if _is_windows():
        return Path.home() / ".royalshuffle_managed_playlists.json"

    return state_folder() / "managed_playlists.json"


def legacy_recovery_file():
    if _is_windows():
        return Path.home() / ".royalshuffle_legacy_recovery.json"

    return state_folder() / "legacy_recovery.json"


def last_playlist_file():
    if _is_windows():
        return Path.home() / ".royalshuffle_last_playlist"

    return state_folder() / "last_playlist"


def royalshuffle_folder():
    return data_folder()


def exports_folder():
    return royalshuffle_folder() / "Exports"


def diagnostics_folder():
    if _is_windows():
        return royalshuffle_folder() / "Diagnostics"

    return state_folder() / "Diagnostics"


def _ensure_folder(path_provider):
    try:
        path = path_provider()
        path.mkdir(parents=True, exist_ok=True)
        return path
    except (OSError, ValueError):
        return None


def ensure_royalshuffle_folder():
    return _ensure_folder(royalshuffle_folder)


def ensure_exports_folder():
    return _ensure_folder(exports_folder)


def ensure_diagnostics_folder():
    return _ensure_folder(diagnostics_folder)
