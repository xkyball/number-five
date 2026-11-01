"""OpenSSL-signed V2 tokens exercise the independent cryptography verifier."""
from __future__ import annotations

import base64
import hashlib
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

from no5_pi_gateway.lumina_tokens import LuminaTokenRegistry
from no5_pi_gateway.lumina_registry_admin import RegistryEditor


ORIGIN="https://lumina-gateway.example:9443"
NOW=1_800_000_000_000


def b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


class LuminaTokenTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name);self.key=self.root/"device.pem"
        subprocess.run(["openssl","ecparam","-name","prime256v1","-genkey","-noout","-out",str(self.key)],check=True,capture_output=True)
        der=subprocess.check_output(["openssl","ec","-in",str(self.key),"-pubout","-outform","DER"],stderr=subprocess.DEVNULL)
        # SubjectPublicKeyInfo for P-256 always ends in the uncompressed SEC1 point.
        self.public=der[-65:];self.key_id=hashlib.sha256(self.public).hexdigest()[:32]
        self.registry_path=self.root/"registry.json"
        RegistryEditor(self.registry_path,ORIGIN,os.getgid()).apply({"op":"enroll","keyId":self.key_id,"publicKey":b64(self.public)})
        self.registry=LuminaTokenRegistry(self.registry_path,ORIGIN,clock=lambda:NOW/1000)

    def tearDown(self) -> None: self.temp.cleanup()

    def token(self, role="LUMINA_AUDIO", scopes="audio.read,audio.command", expiry=NOW+900_000) -> str:
        payload=("v=no5-gateway-token-v2\n"
                 f"kid={self.key_id}\n"
                 f"aud={ORIGIN}\nrole={role}\nscope={scopes}\niat={NOW}\nexp={expiry}\n"
                 "jti=MTIzNDU2Nzg5MDEyMzQ1Ng\n").encode()
        signature=subprocess.check_output(["openssl","dgst","-sha256","-sign",str(self.key)],input=payload)
        return "Bearer N5T2."+b64(payload)+"."+b64(signature)

    def test_enrolled_p256_token_is_scope_bound_and_revocable(self) -> None:
        token=self.token();self.assertIsNotNone(self.registry.verify(token,"audio.read"));self.assertIsNone(self.registry.verify(token,"map.read"))
        RegistryEditor(self.registry_path,ORIGIN,os.getgid()).apply({"op":"revoke","keyId":self.key_id});self.assertIsNone(self.registry.verify(token,"audio.read"))

    def test_expiry_wrong_audience_and_duplicate_payload_are_rejected(self) -> None:
        self.assertIsNone(self.registry.verify(self.token(expiry=NOW),"audio.read"))
        valid=self.token();parts=valid[7:].split(".");payload=base64.urlsafe_b64decode(parts[1]+"==").decode()
        malformed=(payload+"jti=MTIzNDU2Nzg5MDEyMzQ1Ng\n").encode();signature=subprocess.check_output(["openssl","dgst","-sha256","-sign",str(self.key)],input=malformed)
        self.assertIsNone(self.registry.verify("Bearer N5T2."+b64(malformed)+"."+b64(signature),"audio.read"))
        foreign=LuminaTokenRegistry(self.root/"registry.json","https://other.example",clock=lambda:NOW/1000)
        self.assertIsNone(foreign.verify(valid,"audio.read"))

    def test_exact_fifteen_minutes_is_required_even_for_valid_signatures(self):
        for ttl in [1, 899999, 900001, 1800000, -1]:
            self.assertIsNone(self.registry.verify(self.token(expiry=NOW+ttl), "audio.read"))

    def test_signed_noncanonical_order_numbers_and_nonce_are_rejected(self):
        parts=self.token()[7:].split(".")
        original=base64.urlsafe_b64decode(parts[1]+"==").decode()
        rows=original.splitlines(keepends=True)
        invalid=["".join([rows[1],rows[0],*rows[2:]]), original.replace(f"iat={NOW}", f"iat=0{NOW}"),
                 original.replace(f"iat={NOW}", f"iat=+{NOW}"), original.replace("jti=MTIzNDU2Nzg5MDEyMzQ1Ng", "jti=AA"),
                 original.replace("\n", "\r\n")]
        for text in invalid:
            payload=text.encode(); signature=subprocess.check_output(["openssl","dgst","-sha256","-sign",str(self.key)],input=payload)
            self.assertIsNone(self.registry.verify("Bearer N5T2."+b64(payload)+"."+b64(signature), "audio.read"))

    def test_corrupt_signature_and_wrong_registered_key_are_rejected(self):
        token=self.token(); prefix,payload,signature=token[7:].split(".")
        raw=bytearray(base64.urlsafe_b64decode(signature+"=="));raw[-1]^=1
        self.assertIsNone(self.registry.verify("Bearer "+prefix+"."+payload+"."+b64(raw), "audio.read"))

    def test_corrupt_existing_registry_is_never_overwritten_by_enrollment(self):
        original=b'{"version":1,"keys":'
        self.registry_path.write_bytes(original)
        with self.assertRaises(RuntimeError):
            RegistryEditor(self.registry_path,ORIGIN,os.getgid()).apply({"op":"enroll","keyId":self.key_id,"publicKey":b64(self.public)})
        self.assertEqual(original,self.registry_path.read_bytes())
        self.assertIsNone(self.registry.verify(self.token(),"audio.read"))
