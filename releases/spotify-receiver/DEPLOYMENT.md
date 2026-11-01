# Raspberry Pi installation and rollback

This service replaces `no5-raspotify.service` only after its own binary and
gateway contract have passed. On the same host both use the same CamillaDSP
ALSA route and conflict. Independent hosts/VMs can coexist with distinct names.

## Prerequisites and local build

Run these read-only checks on `lysmarine` first:

```sh
command -v cargo rustc pkg-config
rustc --version
pkg-config --modversion alsa
aplay -L | grep -x 'camilladsp'
systemctl is-active camilladsp.service no5-raspotify.service
```

For Debian Bookworm, install missing build prerequisites only after review:

```sh
sudo apt-get update
sudo apt-get install --no-install-recommends cargo rustc build-essential pkg-config libasound2-dev
```

From this project on the Pi, build and run Linux checks:

```sh
make pi-release
cargo test --features alsa
```

`cargo test --features alsa` includes the Unix-socket test that is intentionally
skipped on macOS. It creates no Spotify session and does not touch ALSA output.

## Stage before switching receivers

Choose an immutable release identifier, copy the built binary to its new
directory and validate the unit. This does not stop audio:

```sh
release_id=<reviewed-release-id>
sudo install -d -o root -g root -m 0755 "/opt/no5-spotify-connect/releases/$release_id"
sudo install -o root -g root -m 0755 target/release/no5-spotify-connect \
  "/opt/no5-spotify-connect/releases/$release_id/no5-spotify-connect"
sudo install -o root -g root -m 0644 deploy/systemd/no5-spotify-connect.service \
  /etc/systemd/system/no5-spotify-connect.service
sudo systemd-analyze verify /etc/systemd/system/no5-spotify-connect.service
sudo systemctl daemon-reload
```

Ensure the pre-existing `/var/lib/no5-media` is the reviewed setgid directory
shared by `user`, `no5media` and `no5gateway`; do not loosen it to world
readable/writable. The supplied unit uses a separate automatic
`/var/lib/no5-spotify-connect` for its librepsot cache and preserves its
credentials there. Do not copy that cache or any token into Git or logs.

For an instance-specific public name, create `/etc/no5-spotify-connect/environment`
as root with mode 0644 and set, for example:

```ini
NO5_SPOTIFY_DEVICE_NAME="No5 (MarineOS Lab)"
```

The file and setting are optional: without them the name is `No5`. Names with
spaces stay one argument. Preserve an existing name during upgrades; the example
file in `deploy/` contains only public name configuration. After changing this
file restart only the receiver. Choose distinct names across networked instances.

## Atomic switch and gateway activation

Back up the active gateway configuration and current symlink first. The
gateway's `spotify_connect` config must point at the state and socket paths in
the unit. Start the new service through the symlink, then change the gateway
provider and restart only the gateway. `Conflicts=` stops the legacy receiver
during this step; a short playback interruption is expected.

```sh
sudo ln -sfn "/opt/no5-spotify-connect/releases/$release_id" /opt/no5-spotify-connect/current
sudo cp -a /etc/no5-pi-gateway/config.toml /etc/no5-pi-gateway/config.toml.pre-spotify-connect
sudo systemctl enable --now no5-spotify-connect.service
sudo systemctl is-active no5-spotify-connect.service
sudo systemctl is-active no5-raspotify.service  # expected inactive due Conflicts=
# Edit only the [audio], [audio.raspotify] and [audio.spotify_connect] values
# documented in the gateway DEPLOYMENT.md, then validate it as no5gateway.
sudo -u no5gateway /opt/no5-pi-gateway/current/.venv/bin/no5-pi-gateway --check-config /etc/no5-pi-gateway/config.toml
sudo systemctl restart no5-pi-gateway.service
```

Do not send a gateway audio command just to test installation. Select a real
track through Spotify Connect, then inspect authenticated `GET /v1/audio` for
the distinct real source timestamp and state. Exercise every transport action
once from the app and require a `VERIFIED` acknowledgement plus a newer matching
state. Camilla faders, mute and presets remain separate readback-gated commands.

## Rollback

If the service fails to start, no real player state arrives, audio routing is
not physically accepted, or a transport command is unconfirmed:

```sh
sudo cp -a /etc/no5-pi-gateway/config.toml.pre-spotify-connect /etc/no5-pi-gateway/config.toml
sudo systemctl disable --now no5-spotify-connect.service
sudo systemctl enable --now no5-raspotify.service
sudo systemctl restart no5-pi-gateway.service
```

Keep the staged release and the old receiver unit. Do not delete Spotify cache,
logs, CamillaDSP configuration or gateway state while diagnosing.
