"""Root-owned writer for the otherwise read-only Lumina public-key registry.

The gateway process asks over a bounded Unix socket only after it validated the
operator bootstrap credential. This helper authenticates the peer by kernel
credentials, validates the exact command schema and performs the sole atomic
registry mutation. It never sees tokens, Android private keys or passwords.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import pwd
import socket
import struct
import tempfile

from .lumina_tokens import LuminaTokenRegistry, valid_key

MAX_FRAME = 4096


class RegistryUnavailable(RuntimeError):
    pass


def _atomic_write(path: Path, value: dict, gid: int) -> None:
    path.parent.mkdir(mode=0o750, parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", dir=path.parent, delete=False) as out:
        os.fchmod(out.fileno(), 0o640)
        # main() is root-only. Keeping the editor testable without root does
        # not widen the deployed helper: a non-root process retains its UID.
        os.fchown(out.fileno(), 0 if os.geteuid()==0 else os.geteuid(), gid)
        json.dump(value, out, sort_keys=True, separators=(",", ":")); out.write("\n")
        out.flush(); os.fsync(out.fileno()); temporary = out.name
    try:
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try: os.fsync(directory)
        finally: os.close(directory)
    finally:
        Path(temporary).unlink(missing_ok=True)


class RegistryEditor:
    """Small root-only mutation surface, independently unit-testable."""
    def __init__(self, path: str | Path, audience: str, gateway_gid: int) -> None:
        self.path=Path(path); self.audience=audience; self.gateway_gid=gateway_gid

    def _read(self) -> dict:
        try:return LuminaTokenRegistry(self.path,self.audience)._read()
        except ValueError as error:raise RegistryUnavailable("registry cannot be read safely") from error

    def apply(self, request: dict) -> None:
        if not isinstance(request,dict) or set(request) not in ({"op","keyId","publicKey"},{"op","keyId"}):
            raise ValueError("invalid registry request")
        operation=request.get("op"); key_id=request.get("keyId")
        if operation not in {"enroll","revoke"} or not isinstance(key_id,str): raise ValueError("invalid registry request")
        value=self._read()
        if operation=="enroll":
            public_key=request.get("publicKey")
            if not isinstance(public_key,str): raise ValueError("invalid registry request")
            valid_key(key_id,public_key)
            value["keys"][key_id]={"publicKey":public_key,"revoked":False}
        else:
            if key_id not in value["keys"]: raise ValueError("unknown key")
            value["keys"][key_id]["revoked"]=True
        _atomic_write(self.path,value,self.gateway_gid)


def _read_frame(conn: socket.socket) -> dict:
    conn.settimeout(1.0)
    raw=b""
    while not raw.endswith(b"\n"):
        chunk=conn.recv(MAX_FRAME+1-len(raw))
        if not chunk:raise ValueError("incomplete registry frame")
        raw+=chunk
        if len(raw)>MAX_FRAME:raise ValueError("invalid registry frame")
    if not raw or b"\n" in raw[:-1]:raise ValueError("invalid registry frame")
    value=json.loads(raw[:-1].decode("utf-8"))
    if not isinstance(value,dict): raise ValueError("invalid registry frame")
    return value


def serve(socket_path: Path, editor: RegistryEditor, expected_uid: int, expected_gid: int) -> None:
    socket_path.parent.mkdir(mode=0o750,parents=True,exist_ok=True); socket_path.unlink(missing_ok=True)
    server=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM); server.bind(str(socket_path)); os.chmod(socket_path,0o660); os.chown(socket_path,0,expected_gid); server.listen(8)
    while True:
        conn,_=server.accept()
        with conn:
            try:
                raw=conn.getsockopt(socket.SOL_SOCKET,socket.SO_PEERCRED,struct.calcsize("3i")); pid,uid,gid=struct.unpack("3i",raw)
                if uid!=expected_uid or gid!=expected_gid or pid<=1: raise PermissionError("untrusted peer")
                editor.apply(_read_frame(conn)); conn.sendall(b'{"status":"OK"}\n')
            except (ValueError,PermissionError,OSError,json.JSONDecodeError):
                conn.sendall(b'{"status":"REJECTED"}\n')
            except Exception:
                conn.sendall(b'{"status":"UNAVAILABLE"}\n')


def main() -> None:
    parser=argparse.ArgumentParser(); parser.add_argument("--socket",type=Path,required=True); parser.add_argument("--registry",type=Path,required=True); parser.add_argument("--audience",required=True); parser.add_argument("--gateway-user",default="no5gateway")
    args=parser.parse_args()
    if os.geteuid()!=0: raise SystemExit("Lumina registry helper requires root")
    identity=pwd.getpwnam(args.gateway_user)
    serve(args.socket,RegistryEditor(args.registry,args.audience,identity.pw_gid),identity.pw_uid,identity.pw_gid)

if __name__=="__main__": main()
