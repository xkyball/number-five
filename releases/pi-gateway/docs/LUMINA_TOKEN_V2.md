# Lumina direct gateway tokens V2

This is an additive replacement path for the legacy `X-No5-Gateway-Key` Android
client credential. It is not activated by this document or by a source build.

A one-time bootstrap request, authenticated with the existing operator gateway
credential over canonical HTTPS, registers a Lumina device public P-256 key
under a stable key ID. The registry is root-owned, contains only public keys,
key IDs and revocation state, and is never a copy of Android keys or bootstrap
passwords.

The gateway accepts `Authorization: Bearer N5T2.<payload>.<der-signature>`.
The payload is canonical UTF-8 `key=value` lines and includes version, key ID,
HTTPS origin audience, role, sorted fixed scope list, issue time, expiry time
and random token ID. The gateway verifies P-256/SHA-256 signature, exact
origin, key registration, revocation, 15-minute maximum TTL and endpoint
scope before dispatch. It rejects duplicate credentials, malformed payloads,
unknown keys, expired/future tokens and scopes not applicable to the route.

The Gateway process reads this registry but never writes it. A dedicated root-owned Unix-socket helper authenticates the local `no5gateway` peer and atomically performs the narrowly defined enroll/revoke mutations. The helper never accepts tokens or bootstrap credentials.

The V2 registry permits root-mediated key revocation. Revocation invalidates
all tokens for that key immediately. New enrollment uses a new key ID; no
legacy token or credential is reused. Existing `X-No5-Gateway-Key` clients
remain a separately configured legacy path during migration and must not be
silently treated as V2 clients.
