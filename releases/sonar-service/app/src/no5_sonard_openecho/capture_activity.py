"""Scoped macOS acquisition activity; no global power defaults or sleep override."""
import sys


class _MacActivity:
    def __init__(self):
        import ctypes as c
        self.c = c
        self.foundation = c.CDLL('/System/Library/Frameworks/Foundation.framework/Foundation')
        self.objc = c.CDLL('/usr/lib/libobjc.A.dylib')
        self.objc.objc_getClass.argtypes = [c.c_char_p]
        self.objc.objc_getClass.restype = c.c_void_p
        self.objc.sel_registerName.argtypes = [c.c_char_p]
        self.objc.sel_registerName.restype = c.c_void_p
        self.address = c.cast(self.objc.objc_msgSend, c.c_void_p).value
        self.token = None
        self.info = None

    def send(self, obj, selector, restype, arguments=(), values=()):
        c = self.c
        call = c.CFUNCTYPE(restype, c.c_void_p, c.c_void_p, *arguments)(self.address)
        return call(obj, self.objc.sel_registerName(selector), *values)

    def start(self):
        c = self.c
        pool = self.send(self.objc.objc_getClass(b'NSAutoreleasePool'), b'new', c.c_void_p)
        try:
            self.info = self.send(self.objc.objc_getClass(b'NSProcessInfo'), b'processInfo', c.c_void_p)
            reason = self.send(self.objc.objc_getClass(b'NSString'), b'stringWithUTF8String:', c.c_void_p,
                (c.c_char_p,), (b'User-requested Sonar USB acquisition and finite control leases',))
            # NSActivityUserInitiatedAllowingIdleSystemSleep | NSActivityLatencyCritical.
            # Neither system idle sleep nor display sleep is disabled here.
            options = (0x00FFFFFF & ~(1 << 20)) | 0xFF00000000
            token = self.send(self.info, b'beginActivityWithOptions:reason:', c.c_void_p,
                (c.c_uint64, c.c_void_p), (options, reason))
            if not token:
                raise RuntimeError('macOS declined acquisition activity')
            self.token = self.send(token, b'retain', c.c_void_p)
        finally:
            self.send(pool, b'drain', None)

    def close(self):
        if self.token is not None:
            token, self.token = self.token, None
            self.send(self.info, b'endActivity:', None, (self.c.c_void_p,), (token,))
            self.send(token, b'release', None)


class CaptureActivity:
    def __init__(self, *, platform=None, backend=None):
        self.backend = backend
        self.platform = sys.platform if platform is None else platform
        self.active = False

    def start(self):
        if self.active or self.platform != 'darwin':
            return
        if self.backend is None:
            self.backend = _MacActivity()
        self.backend.start()
        self.active = True

    def close(self):
        if self.active:
            self.active = False
            self.backend.close()
