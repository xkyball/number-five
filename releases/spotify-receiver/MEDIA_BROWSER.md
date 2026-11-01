# Read-only Spotify media candidate

ReadMedia and Browse reuse the current authenticated Connect Session. No account credentials or tokens enter responses. A new receiver/account session invalidates read leases, and results are rechecked before publication. The existing Main FIFO/CAS/concurrency actor and old GatewayState file schema are preserved.

Artwork comes from actual AudioItem covers, restricted to the exact public Spotify image origin. The queue is a bounded observation of the existing Connect actor: at most 10 previous and 80 next entries, complete=false. A passive one-second heartbeat refreshes paused observations and issues no playback, volume or DSP command. Snapshots clear on shutdown.

Browse types: PLAYLISTS, PLAYLIST, LIKED, SEARCH. At most two concurrent readers, a three-second deadline, 1–40 items, offset <=5000, one MiB parsing limit per upstream response, at most eight observed context page reads. Rootlist window offsets must match; URI/search input and observed page pointers are validated. Underlying pinned SpClient buffers before the parsing limit; no hard native transport memory claim. Missing/unsupported private endpoints remain unavailable. Playlist names/track metadata are never invented; unknown labels stay absent. Continuations and unresolved pages are explicit.

Local macOS source checks are recorded under verification/spotify-media-20261009.
The Linux/ALSA build and explicitly authorized deployment in the existing virtual
lab are now complete; see [deployment evidence](evidence/media-deployment-20261009/README.md).
Receiver 27 tests, Connect patch 5 unit + 1 doc, and Gateway 99 tests passed without
skips. Current media is safely unavailable without a fresh connected Spotify
context: real account cover/queue/library/search acceptance remains open.
User confirmations for the earlier plan items 1–3 are recorded separately and do
not create these new runtime results. Optional HOME PKCE/OAuth depends on actual
account access and a supplied public developer client/redirect; no extra login or
playback mutation is introduced here.

References: [pinned upstream](https://github.com/librespot-org/librespot/blob/v0.8.0/core/src/spclient.rs), [Spotify attribution](https://developer.spotify.com/documentation/design), [PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow).
