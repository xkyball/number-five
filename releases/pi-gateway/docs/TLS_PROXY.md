# Boat-LAN TLS proxy

The Pi gateway remains bound to `127.0.0.1:8789`. On lysmarine Nginx exposes
only the authenticated TLS front door on `https://lysmarine.local:9443` and the
equivalent LAN-IP address. Port 9443 was selected because existing listeners
already occupied port 8443; the package default Nginx site is disabled.

The public hostname is deliberately `lysmarine.local`, not `lysmarine`.
The active server certificate must therefore include `DNS:lysmarine.local` as
a SAN before a verifying client can use this address. Changing `server_name`
alone does not make a certificate valid for the new hostname.

## Trust and request authentication

The Pi generates a local CA and a server certificate which must include these
SANs:

- `DNS:lysmarine.local`
- `DNS:lysmarine` (existing client compatibility)
- `IP:192.0.2.10`

The public CA certificate is at
`/etc/no5-gateway-proxy/tls/ca.crt`; its private key and the server key are
root-only. Trust this CA on a deliberately authorised client before using the
HTTPS endpoint. The server certificate is valid for 825 days and must be
rotated before expiry.

Every proxy request, including WebSocket upgrade requests, needs the exact
header `X-No5-Gateway-Key`. Its random value is held only in root-owned
`/etc/nginx/conf.d/no5-gateway-auth.conf` (mode `0640`); do not copy it into
Git, a URL, logs, a screenshot or an Android build. An absent/wrong header
returns HTTP 401 before Nginx forwards anything to the loopback gateway.

HELM Build47 in the separately owned `no-apps` repository supports an
origin-bound imported gateway key and public CA. Its HTTP and SONAR WebSocket
transports use the same security profile, retain certificate and hostname
verification, and refuse cross-origin redirects. Selecting a URL alone does
not install either credential or establish a connection. The gateway service
keeps its authenticated proxy policy unchanged.

For `https://lysmarine.local:9443`, Android 10 uses NSD to resolve an advertised
`_https._tcp` service named exactly `lysmarine`, with the expected hostname and
port. HTTP uses the numeric address only for the socket; the logical origin,
TLS hostname and credential binding remain `lysmarine.local`. Qualified NSD
names and Android 10 numeric resolved hosts are handled by the client. A
missing or mismatched announcement produces a typed diagnostic failure; the
client does not guess a LAN IP or disable TLS checks. This describes the
client's prerequisite, not a newly deployed Avahi configuration. No live Pi
configuration was read or changed during the master integration.

AUDIO `GET` and `POST` use the same gateway key proof. The gateway service
stores only a SHA-256 verifier of the already configured header key, so direct
loopback access cannot bypass the proxy by forging an Nginx marker. There is
no second AUDIO bearer token.

## Nginx files and checks

The repository templates are under `deploy/nginx/`. The production secret map
is intentionally generated on the Pi and excluded from the repository.

```sh
sudo nginx -t
sudo systemctl status nginx.service
curl --cacert /etc/no5-gateway-proxy/tls/ca.crt \
  --resolve lysmarine.local:9443:127.0.0.1 \
  -H 'X-No5-Gateway-Key: <local secret>' \
  https://lysmarine.local:9443/v1/status
```

Do not use `-k` as a client configuration. The local loopback route remains
useful for Pi-only diagnostics and does not expose a LAN listener.

## CamillaGUI browser route

The original full CamillaGUI configuration UI is additionally available only
on `https://lysmarine.local/camilladsp/` over LAN port 443. It does not accept
the gateway header in a browser. Instead, the user enters that existing key on
a local login page once; the loopback gateway verifies its SHA-256 digest and
issues a 15-minute Secure/HttpOnly/SameSite=Strict cookie. Full details,
Origin/CSRF protection, activation and rollback are in
[CAMILLAGUI_BROWSER_ACCESS.md](CAMILLAGUI_BROWSER_ACCESS.md).

## Certificate renewal

`tools/renew_tls_certificate.sh` renews only the server key, CSR and
certificate under the existing local CA. It retains `lysmarine` and the LAN IP
as additional SANs while making `lysmarine.local` the certificate subject and
primary hostname. The script takes root-only backups and restores the prior
server material if `nginx -t` rejects the replacement. It never prints either
private key. If validated staging files remain after an interrupted run, the
root-owned helper can resume only those reviewed files with
`--activate-staged`; it does not silently issue another certificate.

## Rollback

To remove public gateway exposure without touching SONAR, Signal K, CamillaDSP
or the loopback gateway, stop and disable `nginx.service`, remove only the
`no5-gateway` site symlink and No5 `conf.d` files, then run `nginx -t` before
starting any restored web configuration. Preserve the CA and auth-map files
while investigating; rotating the header key means updating every authorised
client configuration.
