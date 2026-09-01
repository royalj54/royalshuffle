import ctypes
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


def documents_folder():
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


def royalshuffle_folder():
    return documents_folder() / "RoyalShuffle"


def exports_folder():
    return royalshuffle_folder() / "Exports"


def diagnostics_folder():
    return royalshuffle_folder() / "Diagnostics"


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
