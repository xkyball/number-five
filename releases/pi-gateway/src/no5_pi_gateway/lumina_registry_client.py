"""Bounded unprivileged client of the root-owned Lumina registry helper."""
from __future__ import annotations
import json
import socket
from .lumina_tokens import valid_key

class RegistryWriteUnavailable(RuntimeError): pass

class LuminaRegistryWriter:
    def __init__(self, path: str) -> None: self.path=path
    def _apply(self,value:dict) -> None:
        raw=json.dumps(value,separators=(",",":"),sort_keys=True).encode()+b"\n"
        if len(raw)>4096: raise ValueError("registry request exceeds bound")
        try:
            with socket.socket(socket.AF_UNIX,socket.SOCK_STREAM) as conn:
                conn.settimeout(2); conn.connect(self.path); conn.sendall(raw); response=conn.recv(128)
        except OSError as error: raise RegistryWriteUnavailable("registry helper unavailable") from error
        if response==b'{"status":"OK"}\n': return
        if response==b'{"status":"REJECTED"}\n': raise ValueError("registry request rejected")
        raise RegistryWriteUnavailable("registry helper unavailable")
    def enroll(self,key_id:str,public_key:str)->None:
        valid_key(key_id,public_key); self._apply({"op":"enroll","keyId":key_id,"publicKey":public_key})
    def revoke(self,key_id:str)->None:
        self._apply({"op":"revoke","keyId":key_id})
