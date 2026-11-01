# Session-bound playlists

The existing authenticated Connect session supplies the saved rootlist, including its observed decorated title, owner username, description, track count, collaboration flag and cover descriptor. Legacy named playlist URIs are canonicalized; folder markers consume their original positions but are not playable playlists. Playlist rows preserve source order. The optional collectionRevision is the rootlist revision, separate from per-page selection identity. Clients stop and mark partial data when that revision changes during pagination. No OAuth login or account credential export is added.

Cover URLs are restricted to fixed i.scdn.co/image or mosaic.scdn.co size/hash paths. The Gateway exposes only session/playlist-bound opaque image IDs and its own HTTPS paths. Downloads carry no Gateway key to the CDN, follow no redirects, reject private resolved addresses and compressed/oversized/non-PNG/JPEG replies, and validate actual bounded image dimensions. Missing source metadata stays absent. Public evidence contains counts only.

The Android adapter accumulates rootlist pages up to the existing 5000-position bound, with cancellation and session-change guards. A local text filter matches loaded title, owner and description; it never issues a Spotify SEARCH. Incomplete/error data is labelled as such. Covers are requested for visible rows, with bounded image/cache/decode work.

PLAYLIST_START carries requestId, receiver session, queue revision, playlist URI, observed first-page revision and an explicit shuffle boolean. Start requires a fresh matching first page/context and an active player. One actor operation performs the immediate queue CAS, context switch and ordered/random start; ordinary selection retains its options. Receiver-thread and actor readback must confirm actual loaded track, entry ID, context and shuffle before VERIFIED. A changed list refuses before write; timeout, lost receipt or changed session yields UNKNOWN without replay. External Spotify clients can still change playback; no cross-client serializability is claimed.

Physical Pi migration is separate. These sources do not authorize a service restart or activate a build.
