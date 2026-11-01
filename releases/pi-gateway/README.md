# No5 Pi Gateway

This service is the Pi-side shared boundary for the existing No5 Android V1
contracts. It is deliberately a gateway, not a replacement for Signal K,
CamillaDSP, Raspotify, or `no5-sonard`.

* SONAR reads the confirmed M0/M1 status and echogram interfaces, preserves raw
  `UInt16` amplitude bins, and emits Android-facing snapshot, WebSocket and
  command acknowledgement shapes.
* HELM reads only an explicitly configured, read-only Signal K snapshot. Every
  output field needs a configured original observation timestamp.
* AUDIO combines a configured CamillaDSP 4.1.3 WebSocket with either the
  legacy librespot/Raspotify `--onevent` observer or the separate No5 Spotify
  Connect receiver. The latter has a local Unix control socket and confirms
  transport only through fresh player readback. Neither path invents a media
  clock, and Android volume is never the system master.
  Physical fader labels and validated preset IDs are described in
  [docs/AUDIO_CATALOG.md](docs/AUDIO_CATALOG.md); preset application requires
  post-reload readback and keeps the deployment profile's writes disabled.
* Weather can deliver a real Open-Meteo forecast only from a separately
  configured fresh Signal K boat position; no fallback location is used.
* An opt-in, loopback-only local control surface can overlay selected HELM
  fields, provide an isolated weather test scene or switch SONAR to a clearly
  simulated internal test stream. It has no hardware write path; see
  [docs/LOCAL_SIMULATION.md](docs/LOCAL_SIMULATION.md).

The default configuration has no producer URL, so it starts unavailable rather
than fabricating data.  M0 is always exposed as `SIMULATED`.  M1 is exposed as
`REAL` raw Pico-host transport, but the sample-timing calibration remains
invalid until the physical Venterior path and calibration are accepted.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md),
[docs/CENTRAL_GATEWAY.md](docs/CENTRAL_GATEWAY.md), and
[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md). The LAN TLS/header-auth setup and
current Android client boundary are documented in [docs/TLS_PROXY.md](docs/TLS_PROXY.md).
The separately authenticated original CamillaGUI browser route is documented
in [docs/CAMILLAGUI_BROWSER_ACCESS.md](docs/CAMILLAGUI_BROWSER_ACCESS.md).
The active physical-Pi transfer of the lab's simulator/browser proxy and
its verification are in [docs/PI_BROWSER_PROXY.md](docs/PI_BROWSER_PROXY.md).
The active shared key/password proxy, public-CA bootstrap and pending personal verifier are in
[docs/PASSWORD_AND_CA_BOOTSTRAP.md](docs/PASSWORD_AND_CA_BOOTSTRAP.md).

## Local verification

```sh
make test
make package
```

The runtime dependency is `aiohttp`; the unit suite uses only the Python
standard library and does not contact a Pi or write to an upstream daemon.
For an additional read-only check against the current separately owned Android
client contracts, install the `test` extra and run:

```sh
NO5_HELM_CLIENT_ROOT=/absolute/path/to/no-apps/helm make test
```

## Optional read-only Spotify media

GET `/v1/audio/media`, `/v1/audio/artwork/{id}` and `/v1/audio/browse` use the existing AUDIO gateway key and the configured native receiver socket. The previous AUDIO V1 schema is unchanged. Image IDs bind actual track and receiver session; CDN URLs never cross to Android. TLS verifies the fixed public CDN, private DNS answers/redirects/cookies/encoded responses are rejected, and binary replies are capped at one MiB and one megapixel. Runtime-only cache holds at most eight images. Browsing never acquires the Main write lock or sends a playback command. See [`MEDIA_BROWSER.md`](../no5-spotify-connect/MEDIA_BROWSER.md) for bounds and activation limits.
