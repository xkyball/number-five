# Aktuelle Erweiterung · 10. Oktober 2026

Auf echtem Pi und Labor verwendet die vorhandene einzelne Browser-Eingabe nun
„Gateway-Key oder Passwort“ mit lokaler Ableitung und dem nginx-geschützten
`/camilladsp/password-session`. Der ursprüngliche Cookie-Prüfer und Logout
bleiben bestehen. Der persönliche Passwort-Prüfwert fehlt noch. Aktuelles
Verhalten und Belege: [PASSWORD_AND_CA_BOOTSTRAP.md](PASSWORD_AND_CA_BOOTSTRAP.md).
Die nachfolgende Beschreibung dokumentiert die ursprüngliche Key-Einführung.

# CamillaGUI browser access on lysmarine

This is the dedicated browser route for the already installed CamillaGUI
backend. It intentionally does **not** replace the No5 app AUDIO adapter. The
app continues to use `/v1/audio`; CamillaGUI remains the operator-facing full
DSP configuration surface.

## Delivered boundary

```text
Browser
  https://lysmarine.local/camilladsp/
       │ TLS :443, existing local CA certificate
       ▼
Nginx on lysmarine
  - login with existing No5 gateway key
  - 15-minute Secure/HttpOnly/SameSite=Strict cookie
  - exact Origin protection for all writes
       ▼
127.0.0.1:8789/internal/camillagui/*
  - compares only SHA-256 verifier of the existing key
  - HMAC-signs a session bound to this gateway process
       ▼
127.0.0.1:5005 CamillaGUI
       ▼
127.0.0.1:1234 CamillaDSP
```

`camillagui.service` runs as `user:camilladsp`, but its config uses
`bind_address: "127.0.0.1"`. It has no LAN listener. Since the physical-Pi
browser migration on 10 October, Nginx binds loopback and the assigned global
addresses of the selected Pi LAN interfaces, currently `192.0.2.10:443`.
It serves CamillaGUI and the separately authenticated simulator on that port;
the general app API stays on 9443. See [PI_BROWSER_PROXY.md](PI_BROWSER_PROXY.md).

The existing Nginx certificate already covers `lysmarine.local`. The browser
must trust the No5 local CA first. Open the URL above, enter the existing
gateway key in the password field, and use the original CamillaGUI UI. The key
is not placed in a URL, log line, local storage, HTML or cookie. A valid login
sets `no5_camillagui` only for `/camilladsp/`; it expires after 900 seconds and
also becomes invalid when `no5-pi-gateway` restarts. `POST /camilladsp/logout`
clears it; a browser UI may also simply close the tab or wait for expiry.

CamillaGUI's current bundled JavaScript emits root-relative `/api/` calls.
Nginx removes compression and rewrites those paths to `/camilladsp/api/` in
JavaScript responses; the upstream prefix is then stripped before it reaches
the loopback backend. Upgrade headers are preserved for any present/future GUI
WebSocket route.

## Security and write scope

This is full CamillaGUI access, so its configuration writes are intentional
after login. The boundary has the following restrictions:

- direct `:5005` access is impossible outside the Pi;
- every unsafe GUI request (`POST`, `PUT`, `PATCH`, `DELETE`) requires
  `Origin: https://lysmarine.local`; a missing or other Origin is rejected;
- Nginx's `auth_request` forwards only the session cookie to the loopback
  validator, never the login body or raw gateway key;
- `/internal/` is an external Nginx `404`; `/admin/` stays unavailable on the
  normal `:9443` gateway listener;
- login attempts are limited to five per minute per client address (burst two);
- ordinary Nginx access logs record request metadata, not request bodies or
  cookie values. Do not turn on debug logging while entering the key.

The session secret is a root-only random signing secret
`NO5_CAMILLAGUI_SESSION_SECRET` in `/etc/no5-pi-gateway/environment`. It is not
a second operator password and must never be copied to a client.

## Install transaction

The deployment must use a new versioned gateway release containing the browser
session code and the Nginx/static files in `deploy/nginx/`. Do not enable the
`[camilla_gui]` table on an older release.

1. Back up, root-only, the active gateway TOML/environment, the current Nginx
   site, and the original CamillaGUI systemd unit/configuration.
2. Install the new gateway release atomically, add the tracked `[camilla_gui]`
   table shown in `deploy/lysmarine/config.toml`, and generate the session
   secret locally only if absent. Its environment file remains `0600 root:root`.
   Keep the existing `NO5_GATEWAY_HEADER_KEY_SHA256`; neither this operation nor
   the service needs the raw header key.
3. Copy `01-no5-camillagui-map.conf` into `/etc/nginx/conf.d/`, the three login
   assets into `/usr/local/share/no5-gateway/`, and the revised site into
   `/etc/nginx/sites-available/no5-gateway` using root-owned `0644` files.
4. Copy the existing CamillaGUI YAML to `/etc/camillagui/no5.yml`, changing
   only `bind_address` from `0.0.0.0` to `127.0.0.1`. Install a systemd drop-in
   which clears `ExecStart` and starts the existing binary with
   `-c /etc/camillagui/no5.yml`; do not edit the vendor unit in place.
5. Before activation, validate the new gateway configuration under the
   `no5gateway` account and run `/usr/sbin/nginx -t`. Only then run
   `systemctl daemon-reload`, restart `no5-pi-gateway`, start/restart
   `camillagui`, and reload Nginx.

The live transaction records root-only backup paths with a timestamp. It does
not restart CamillaDSP, alter its DSP configuration, send a fader/transport
command, modify the Spotify receiver, or change audio routing.

## Acceptance checks

Use only the public CA and normal certificate verification; do not use `-k`.

1. `https://lysmarine.local:443/camilladsp/login` returns the local login page.
2. `https://lysmarine.local:443/camilladsp/` without a cookie redirects to the
   login page; `/internal/camillagui/auth` is not externally reachable.
3. A local synthetic unit test covers valid login, wrong key, wrong Origin,
   expiry, restart/session binding and logout. Live deployment deliberately
   does **not** submit an operator key.
4. The operator manually logs in once and checks the GUI's normal read screen,
   a non-destructive navigation, a configuration save only if desired, and
   logout/15-minute expiry. No test creates an unwanted DSP write.

## Rollback

If Nginx validation, GUI startup or a browser acceptance check fails, restore
the timestamped Nginx site, map file, login assets, CamillaGUI config/drop-in,
gateway TOML/environment and `/opt/no5-pi-gateway/current` symlink. Then run
`nginx -t`, `systemctl daemon-reload`, restart only `no5-pi-gateway` and
CamillaGUI, and reload Nginx. CamillaDSP, SONAR, Signal K, Spotify Connect and
the audio routing remain untouched.
