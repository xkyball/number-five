//! Bounded read-only metadata, tied to one existing Connect session.

use crate::{control::DispatchError, state::PlaybackState};
use futures_util::{StreamExt, stream};
use librespot::metadata::{Episode, Metadata, Track};
use librespot::{
    connect::QueueSnapshot,
    core::{Session, SpotifyUri},
    metadata::audio::item::CoverImage,
};
use protobuf::Message;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha1::{Digest, Sha1};
use std::collections::VecDeque;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::{Mutex, Semaphore};

const MAX_BROWSE_BYTES: usize = 1_048_576;

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Artwork {
    pub url: String,
    pub width: u32,
    pub height: u32,
}

pub fn select_artwork(covers: &[CoverImage]) -> Option<Artwork> {
    let valid = |cover: &&CoverImage| {
        let Some(id) = cover.url.strip_prefix("https://i.scdn.co/image/") else {
            return false;
        };
        (32..=128).contains(&id.len())
            && id.bytes().all(|c| c.is_ascii_alphanumeric())
            && (1..=2048).contains(&cover.width)
            && (1..=2048).contains(&cover.height)
            && cover.width * cover.height <= 1_048_576
    };
    let covers: Vec<_> = covers.iter().filter(valid).take(16).collect();
    let cover = covers
        .iter()
        .filter(|c| c.width <= 640 && c.height <= 640)
        .max_by_key(|c| c.width * c.height)
        .or_else(|| covers.iter().min_by_key(|c| c.width * c.height))?;
    Some(Artwork {
        url: cover.url.clone(),
        width: cover.width as u32,
        height: cover.height as u32,
    })
}

pub fn media_payload(
    receiver_session: &str,
    state: &PlaybackState,
    queue: Option<QueueSnapshot>,
) -> Value {
    let available = state.connected && state.active;
    let queue = queue.filter(|q| available && q.active).map(|q| json!({
        "revision": if q.revision.is_empty() { None } else { Some(q.revision) },
        "contextUri": valid_context_uri(&q.context_uri).then_some(q.context_uri),
        "observedAtEpochMs": q.observed_at_epoch_ms,
        "previous": q.previous.iter().filter(|item| valid_playable_uri(&item.uri)).map(|item|
            json!({"uri":item.uri,"title":item.title,"artist":item.artist})).collect::<Vec<_>>(),
        "next": q.next.iter().filter(|item| valid_playable_uri(&item.uri)).map(|item|
            json!({"uri":item.uri,"title":item.title,"artist":item.artist})).collect::<Vec<_>>(),
        "complete": false
    }));
    json!({
        "version":1,"kind":"spotify-media","receiverSessionId":receiver_session,
        "available":available,"observedAtEpochMs":state.observed_at_epoch_ms,
        "track": if available { Some(json!({"id":state.track_id,"uri":state.track_uri,
            "title":state.title,"artists":state.artists,"album":state.album,"durationMs":state.duration_ms})) } else {None},
        "artwork": if available && state.track_uri.is_some() { state.artwork.as_ref() } else {None}, "queue":queue
    })
}

fn valid_id(value: &str) -> bool {
    value.len() == 22 && value.bytes().all(|c| c.is_ascii_alphanumeric())
}
pub fn valid_playable_uri(uri: &str) -> bool {
    ["spotify:track:", "spotify:episode:"]
        .iter()
        .any(|prefix| uri.strip_prefix(prefix).is_some_and(valid_id))
}
pub fn valid_context_uri(uri: &str) -> bool {
    valid_playable_uri(uri)
        || ["spotify:playlist:", "spotify:album:", "spotify:artist:"]
            .iter()
            .any(|prefix| uri.strip_prefix(prefix).is_some_and(valid_id))
}
fn text(value: &str) -> Option<String> {
    let value = value.trim();
    (!value.is_empty() && value.len() <= 200 && !value.chars().any(char::is_control))
        .then(|| value.into())
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(tag = "type", rename_all = "SCREAMING_SNAKE_CASE", deny_unknown_fields)]
pub enum BrowseQuery {
    Playlists {
        offset: u32,
        limit: u32,
    },
    Playlist {
        uri: String,
        offset: u32,
        limit: u32,
    },
    Liked {
        offset: u32,
        limit: u32,
    },
    Search {
        query: String,
        offset: u32,
        limit: u32,
    },
}
impl BrowseQuery {
    pub fn validate(&self) -> Result<(), DispatchError> {
        let (offset, limit) = self.window();
        if offset > 5000 || !(1..=40).contains(&limit) {
            return Err(rejected("Browse page is outside the supported bounds"));
        }
        match self {
            Self::Playlist { uri, .. }
                if !uri.strip_prefix("spotify:playlist:").is_some_and(valid_id) =>
            {
                Err(rejected("Playlist URI is invalid"))
            }
            Self::Search { query, .. } if text(query).is_none() => {
                Err(rejected("Search text is invalid"))
            }
            _ => Ok(()),
        }
    }
    fn window(&self) -> (u32, u32) {
        match self {
            Self::Playlists { offset, limit }
            | Self::Playlist { offset, limit, .. }
            | Self::Liked { offset, limit }
            | Self::Search { offset, limit, .. } => (*offset, *limit),
        }
    }
}

#[derive(Clone)]
pub struct BrowseSession {
    pub id: String,
    pub session: Session,
}
pub type SharedBrowseSession = Arc<Mutex<Option<BrowseSession>>>;

pub struct Browser {
    pub sessions: SharedBrowseSession,
    slots: Semaphore,
    labels: Mutex<VecDeque<(String, String, Instant, Option<(String, Option<String>)>)>>,
}
impl Browser {
    pub fn new(sessions: SharedBrowseSession) -> Self {
        Self {
            sessions,
            slots: Semaphore::new(2),
            labels: Mutex::new(VecDeque::new()),
        }
    }
    pub async fn read(&self, expected: &str, query: BrowseQuery) -> Result<Value, DispatchError> {
        query.validate()?;
        let _slot = self
            .slots
            .try_acquire()
            .map_err(|_| rejected("Browse readers are busy"))?;
        let active = self
            .sessions
            .lock()
            .await
            .clone()
            .ok_or_else(|| rejected("Connect session is unavailable"))?;
        if active.id != expected || active.session.is_invalid() {
            return Err(rejected("Connect session changed"));
        }
        let mut result = tokio::time::timeout(
            std::time::Duration::from_secs(2),
            fetch(&active.session, &query),
        )
        .await
        .map_err(|_| rejected("Browse read deadline exceeded"))??;
        result["revision"] = Value::String(browse_revision(&query, &result));
        self.enrich_items(&active, &mut result["items"]).await;
        let sessions = self.sessions.lock().await;
        if !sessions
            .as_ref()
            .is_some_and(|s| s.id == expected && !s.session.is_invalid())
        {
            return Err(rejected("Connect session changed during the read"));
        }
        Ok(
            json!({"version":1,"kind":"spotify-browse","receiverSessionId":expected,
            "observedAtEpochMs":crate::state::current_epoch_ms(),"page":result}),
        )
    }

    /// Metadata I/O is bounded and runs outside the playback and Main actors.
    pub async fn enrich_media(&self, expected: &str, value: &mut Value) {
        let Some(active) = self
            .sessions
            .lock()
            .await
            .clone()
            .filter(|s| s.id == expected)
        else {
            return;
        };
        if let Some(queue) = value.get_mut("queue").filter(|q| !q.is_null()) {
            self.enrich_items(&active, &mut queue["next"]).await;
        }
    }

    async fn enrich_items(&self, active: &BrowseSession, items: &mut Value) {
        let Some(rows) = items.as_array_mut() else {
            return;
        };
        let wanted: Vec<_> = rows
            .iter()
            .filter(|r| r["title"].is_null())
            .filter_map(|r| r["uri"].as_str())
            .filter(|uri| valid_playable_uri(uri))
            .take(40)
            .map(str::to_owned)
            .collect();
        let work = stream::iter(wanted.into_iter().map(|uri| async move {
            let labels = self.labels(active, &uri).await;
            (uri, labels)
        }))
        .buffer_unordered(4);
        tokio::pin!(work);
        let deadline = tokio::time::sleep(Duration::from_millis(1500));
        tokio::pin!(deadline);
        loop {
            tokio::select! {
                _ = &mut deadline => break,
                item = work.next() => match item {
                    Some((uri, Some((title, artist)))) => for row in rows.iter_mut().filter(|r| r["uri"].as_str()==Some(&uri)) {
                        row["title"] = json!(title);
                        row["artist"] = json!(artist);
                    },
                    Some(_) => {},
                    None => break,
                }
            }
        }
    }

    async fn labels(&self, active: &BrowseSession, uri: &str) -> Option<(String, Option<String>)> {
        {
            let mut cache = self.labels.lock().await;
            cache.retain(|(session, _, stamp, result)| {
                session == &active.id
                    && stamp.elapsed()
                        < Duration::from_secs(if result.is_some() { 600 } else { 15 })
            });
            if let Some((_, _, _, result)) = cache
                .iter()
                .find(|(s, u, _, _)| s == &active.id && u == uri)
            {
                return result.clone();
            }
        }
        let id = SpotifyUri::from_uri(uri).ok()?;
        let result = tokio::time::timeout(Duration::from_millis(1200), async {
            if uri.starts_with("spotify:track:") {
                let track = Track::get(&active.session, &id).await.ok()?;
                let artist = text(
                    &track
                        .artists
                        .iter()
                        .take(10)
                        .map(|a| a.name.clone())
                        .collect::<Vec<_>>()
                        .join(", "),
                );
                Some((text(&track.name)?, artist))
            } else {
                let episode = Episode::get(&active.session, &id).await.ok()?;
                Some((text(&episode.name)?, text(&episode.show_name)))
            }
        })
        .await
        .ok()
        .flatten();
        let mut cache = self.labels.lock().await;
        if cache.len() >= 256 {
            cache.pop_front();
        }
        cache.push_back((
            active.id.clone(),
            uri.into(),
            Instant::now(),
            result.clone(),
        ));
        result
    }

    /// Resolve a fresh context and the exact observed row; never use load's index fallback.
    pub async fn selection_context(
        &self,
        expected: &str,
        query: BrowseQuery,
        expected_revision: Option<String>,
        row: usize,
        expected_uri: &str,
    ) -> Result<(librespot::protocol::context::Context, usize), DispatchError> {
        query.validate()?;
        if matches!(query, BrowseQuery::Playlists { .. })
            || !valid_playable_uri(expected_uri)
            || row >= query.window().1 as usize
        {
            return Err(rejected("Selection target is invalid"));
        }
        let _slot = self
            .slots
            .try_acquire()
            .map_err(|_| rejected("Browse readers are busy"))?;
        let active = self
            .sessions
            .lock()
            .await
            .clone()
            .filter(|s| s.id == expected && !s.session.is_invalid())
            .ok_or_else(|| rejected("Connect session changed"))?;
        let context_uri = match &query {
            BrowseQuery::Playlist { uri, .. } => uri.clone(),
            BrowseQuery::Liked { .. } => format!(
                "spotify:user:{}:collection",
                escape_query(&active.session.username())
            ),
            BrowseQuery::Search { query, .. } => format!("spotify:search:{}", escape_query(query)),
            BrowseQuery::Playlists { .. } => unreachable!(),
        };
        let (offset, limit) = query.window();
        let mut context = read_context_window(&active.session, &context_uri, offset, limit).await?;
        // Search ranking depends on request query parameters. Browse and selection
        // must use the same canonical context request, and selection must validate
        // the very context it will apply rather than fetch a second result set.
        let page = if matches!(query, BrowseQuery::Playlist { .. }) {
            fetch(&active.session, &query).await?
        } else {
            context_page(&context, offset, limit)?
        };
        let position = collection_position(
            &query,
            &page,
            &context,
            expected_revision.as_deref(),
            row,
            expected_uri,
        )?;
        let tracks: Vec<_> = context
            .pages
            .iter()
            .flat_map(|p| p.tracks.iter())
            .cloned()
            .collect();
        // Fold already loaded pages into one exact page, preserving a real continuation pointer.
        let pointer =
            context.pages.iter().rev().find_map(|p| {
                (!p.next_page_url().is_empty()).then(|| p.next_page_url().to_owned())
            });
        let mut first = librespot::protocol::context_page::ContextPage::new();
        first.tracks = tracks;
        context.pages = vec![first];
        if let Some(pointer) = pointer {
            if !safe_page_uri(&pointer) {
                return Err(rejected("Selected context continuation is unsupported"));
            }
            let mut continuation = librespot::protocol::context_page::ContextPage::new();
            continuation.page_url = Some(pointer);
            context.pages.push(continuation);
        }
        context.uri = Some(context_uri.clone());
        context.url = Some(format!("context://{context_uri}"));
        if !self
            .sessions
            .lock()
            .await
            .as_ref()
            .is_some_and(|s| s.id == expected && !s.session.is_invalid())
        {
            return Err(rejected(
                "Connect session changed during selection preparation",
            ));
        }
        Ok((context, position))
    }
}
fn rejected(message: &str) -> DispatchError {
    DispatchError::Rejected(message.into())
}

async fn fetch(session: &Session, query: &BrowseQuery) -> Result<Value, DispatchError> {
    let (offset, limit) = query.window();
    if let BrowseQuery::Playlists { .. } | BrowseQuery::Playlist { .. } = query {
        let bytes = match query {
            BrowseQuery::Playlists { .. } => {
                session
                    .spclient()
                    .get_rootlist(offset as usize, Some(limit as usize))
                    .await
            }
            BrowseQuery::Playlist { uri, .. } => {
                let SpotifyUri::Playlist { id, .. } =
                    SpotifyUri::from_uri(uri).map_err(|_| rejected("Playlist URI is invalid"))?
                else {
                    return Err(rejected("Playlist URI is invalid"));
                };
                session.spclient().get_playlist(&id).await
            }
            _ => unreachable!(),
        }
        .map_err(|_| rejected("Existing-session playlist access is unavailable"))?;
        if bytes.len() > MAX_BROWSE_BYTES {
            return Err(rejected("Playlist reply exceeds the parsing limit"));
        }
        let list =
            librespot::protocol::playlist4_external::SelectedListContent::parse_from_bytes(&bytes)
                .map_err(|_| rejected("Playlist reply is invalid"))?;
        return playlist_page(
            &list,
            offset,
            limit,
            matches!(query, BrowseQuery::Playlists { .. }),
        );
    }
    let uri = match query {
        BrowseQuery::Liked { .. } => format!(
            "spotify:user:{}:collection",
            escape_query(&session.username())
        ),
        BrowseQuery::Search { query, .. } => format!("spotify:search:{}", escape_query(query)),
        _ => unreachable!(),
    };
    let context = read_context_window(session, &uri, offset, limit).await?;
    context_page(&context, offset, limit)
}

async fn read_context_window(
    session: &Session,
    uri: &str,
    offset: u32,
    limit: u32,
) -> Result<librespot::protocol::context::Context, DispatchError> {
    // get_context omits metrics and salt. get_next_page appends both, which
    // changes Spotify search results even for the same search text.
    let context = session
        .spclient()
        .get_context(uri)
        .await
        .map_err(|_| rejected("Existing-session context access is unavailable"))?;
    if context.compute_size() > MAX_BROWSE_BYTES as u64 {
        return Err(rejected("Context exceeds the parsing limit"));
    }
    resolve_pages(session, context, offset, limit).await
}

fn collection_position(
    query: &BrowseQuery,
    page: &Value,
    context: &librespot::protocol::context::Context,
    expected_revision: Option<&str>,
    row: usize,
    expected_uri: &str,
) -> Result<usize, DispatchError> {
    if Some(browse_revision(query, page)).as_deref() != expected_revision
        || page["items"].get(row).and_then(|i| i["uri"].as_str()) != Some(expected_uri)
    {
        return Err(rejected("Collection changed; selection was not sent"));
    }
    let (offset, limit) = query.window();
    let tracks: Vec<_> = context.pages.iter().flat_map(|p| &p.tracks).collect();
    let position = tracks
        .iter()
        .enumerate()
        .skip(offset as usize)
        .take(limit as usize)
        .filter(|(_, t)| valid_playable_uri(t.uri()))
        .map(|(i, _)| i)
        .nth(row)
        .ok_or_else(|| rejected("Selected row is unavailable"))?;
    if tracks[position].uri() != expected_uri {
        return Err(rejected("Selected context row changed"));
    }
    Ok(position)
}

/// Follow only observed pointers, relative to SpClient's fixed Spotify origin.
fn safe_page_uri(value: &str) -> bool {
    value.starts_with("hm://")
        && value.len() <= 2048
        && !value.contains("..")
        && value
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || b":/?=&%._~-".contains(&c))
}
async fn resolve_pages(
    session: &Session,
    mut context: librespot::protocol::context::Context,
    offset: u32,
    limit: u32,
) -> Result<librespot::protocol::context::Context, DispatchError> {
    let mut seen = std::collections::HashSet::new();
    for _ in 0..8 {
        if context.pages.len() > 8 || context.pages.iter().any(|p| p.tracks.len() > 5000) {
            return Err(rejected("Context exceeds the parsing bounds"));
        }
        let loaded: usize = context.pages.iter().map(|p| p.tracks.len()).sum();
        if loaded >= (offset + limit) as usize {
            break;
        }
        let pointer = context.pages.iter().enumerate().find_map(|(index, page)| {
            if page.tracks.is_empty() && !page.page_url().is_empty() {
                Some((index, true, page.page_url().to_owned()))
            } else if !page.next_page_url().is_empty() {
                Some((index, false, page.next_page_url().to_owned()))
            } else {
                None
            }
        });
        let Some((index, replace, pointer)) = pointer else {
            break;
        };
        if !safe_page_uri(&pointer) || !seen.insert(pointer.clone()) {
            return Err(rejected("Context page pointer is unsupported"));
        }
        let bytes = session
            .spclient()
            .get_next_page(&pointer)
            .await
            .map_err(|_| rejected("Existing-session context page is unavailable"))?;
        if bytes.len() > MAX_BROWSE_BYTES {
            return Err(rejected("Context page exceeds the parsing limit"));
        }
        let json = std::str::from_utf8(&bytes).map_err(|_| rejected("Context page is invalid"))?;
        let page = protobuf_json_mapping::parse_from_str::<
            librespot::protocol::context_page::ContextPage,
        >(json)
        .map_err(|_| rejected("Context page is invalid"))?;
        if replace {
            context.pages[index] = page;
        } else {
            context.pages[index].next_page_url = None;
            context.pages.push(page);
        }
    }
    Ok(context)
}

/// Rootlists can retain legacy named playlist URIs; canonicalize without dropping them.
fn canonical_playlist_uri(uri: &str) -> Option<String> {
    let SpotifyUri::Playlist { id, .. } = SpotifyUri::from_uri(uri).ok()? else { return None; };
    let value = format!("spotify:playlist:{}", id.to_base62().ok()?);
    valid_context_uri(&value).then_some(value)
}
fn description(value: &str) -> Option<String> {
    let mut markup = false;
    let plain: String = value.chars().filter(|c| {
        if *c == '<' { markup = true; return false; }
        if *c == '>' { markup = false; return false; }
        !markup
    }).map(|c| if c.is_control() { ' ' } else { c }).collect();
    text(&plain.split_whitespace().collect::<Vec<_>>().join(" ").chars().take(190).collect::<String>())
}
fn playlist_image_url(value: &str) -> Option<String> {
    let safe = if let Some(id) = value.strip_prefix("https://i.scdn.co/image/") {
        (32..=128).contains(&id.len()) && id.bytes().all(|c| c.is_ascii_alphanumeric())
    } else if let Some(path) = value.strip_prefix("https://mosaic.scdn.co/") {
        path.split_once('/').is_some_and(|(size, id)| matches!(size,"100"|"300"|"640")
            && (40..=320).contains(&id.len()) && id.bytes().all(|c| c.is_ascii_hexdigit()))
    } else { false };
    safe.then(|| value.to_owned())
}
fn playlist_cover(a: &librespot::protocol::playlist4_external::ListAttributes) -> Option<String> {
    a.picture_size.iter().take(16).filter_map(|p| playlist_image_url(p.url())).next()
        .or_else(|| if a.picture().len() == 20 {
            Some(format!("https://i.scdn.co/image/{}", a.picture().iter().map(|b| format!("{b:02x}")).collect::<String>()))
        } else { std::str::from_utf8(a.picture()).ok().and_then(playlist_image_url) })
}

pub fn playlist_page(
    list: &librespot::protocol::playlist4_external::SelectedListContent,
    offset: u32,
    limit: u32,
    root: bool,
) -> Result<Value, DispatchError> {
    let contents = list
        .contents
        .as_ref()
        .ok_or_else(|| rejected("Playlist contains no observed item list"))?;
    if contents.pos() < 0
        || (root && contents.pos() as u32 != offset)
        || (!root && contents.pos() != 0)
    {
        return Err(rejected(
            "Playlist window does not match the requested offset",
        ));
    }
    let start = if root { 0 } else { offset as usize };
    let items: Vec<_> = contents.items.iter().enumerate().skip(start).take(limit as usize)
        .filter_map(|(index, item)| {
            let uri = if root { canonical_playlist_uri(item.uri())? }
                else if valid_playable_uri(item.uri()) { item.uri().to_owned() } else { return None; };
            let meta = contents.meta_items.get(index);
            let attributes = meta.and_then(|m| m.attributes.as_ref());
            let name = attributes.and_then(|a| text(a.name()));
            let mut row = json!({"uri":uri,"title":name,"artist":null});
            if root {
                row["playlist"] = json!({
                    "owner":meta.and_then(|m| text(m.owner_username())),
                    "description":attributes.and_then(|a| description(a.description())),
                    "trackCount":meta.and_then(|m| m.length).filter(|n| (0..=1_000_000).contains(n)),
                    "collaborative":attributes.and_then(|a| a.collaborative),
                    "artwork":attributes.and_then(playlist_cover).map(|url| json!({"url":url}))
                });
            }
            Some(row)
        }).collect();
    let available = contents
        .items
        .len()
        .saturating_sub(start)
        .min(limit as usize) as u32;
    let next = offset + available;
    let more = contents.truncated()
        || list.length() > next as i32
        || contents.items.len() > start + available as usize;
    let revision = list
        .revision()
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect::<String>();
    Ok(
        json!({"offset":offset,"limit":limit,"revision":if revision.is_empty(){None}else{Some(revision.clone())},
        "items":items,"nextOffset":if more && available > 0 {Some(next)}else{None},"complete":!more,
        "collectionRevision":if root && !revision.is_empty() {Some(revision)} else {None}}),
    )
}

pub fn context_page(
    context: &librespot::protocol::context::Context,
    offset: u32,
    limit: u32,
) -> Result<Value, DispatchError> {
    if context.pages.len() > 8 || context.pages.iter().any(|p| p.tracks.len() > 5000) {
        return Err(rejected("Context exceeds the parsing bounds"));
    }
    let tracks: Vec<_> = context.pages.iter().flat_map(|p| &p.tracks).collect();
    let unresolved = context.pages.iter().any(|p| {
        (p.tracks.is_empty() && !p.page_url().is_empty())
            || !p.next_page_url().is_empty()
            || p.loading()
    });
    let items: Vec<_> = tracks
        .iter()
        .skip(offset as usize)
        .take(limit as usize)
        .filter_map(|item| {
            let uri = item.uri();
            valid_playable_uri(uri).then(|| {
                json!({"uri":uri,
            "title":item.metadata.get("title").and_then(|v|text(v)),
            "artist":item.metadata.get("artist_name").and_then(|v|text(v))})
            })
        })
        .collect();
    let consumed = tracks
        .len()
        .saturating_sub(offset as usize)
        .min(limit as usize) as u32;
    let next = offset + consumed;
    let local_more = tracks.len() > next as usize;
    Ok(
        json!({"offset":offset,"limit":limit,"revision":format!("{:x}", Sha1::digest(
            serde_json::to_vec(&tracks.iter().map(|t| (t.uri(), t.uid(), t.gid())).collect::<Vec<_>>())
                .expect("bounded context identity"))),"items":items,
        "nextOffset":if local_more && consumed>0 {Some(next)}else{None},
        "complete":!local_more && !unresolved,"unresolvedPages":unresolved}),
    )
}
// Opaque ordering identity, not an authentication primitive. Label enrichment
// is deliberately excluded so a cache completion cannot change selection identity.
fn browse_revision(query: &BrowseQuery, page: &Value) -> String {
    let uris: Vec<_> = page["items"]
        .as_array()
        .into_iter()
        .flatten()
        .map(|v| v["uri"].clone())
        .collect();
    format!(
        "no5-{:x}",
        Sha1::digest(
            serde_json::to_vec(&json!({
                "query": query, "source": page["revision"], "items": uris,
                "next": page["nextOffset"], "complete": page["complete"]
            }))
            .expect("bounded serializable identity")
        )
    )
}

fn escape_query(value: &str) -> String {
    value
        .bytes()
        .map(|b| {
            if b.is_ascii_alphanumeric() || b"-_.~".contains(&b) {
                (b as char).to_string()
            } else {
                format!("%{b:02X}")
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn search_selection_binds_the_exact_fresh_window_and_duplicate_occurrence() {
        let uri = "spotify:track:4uLU6hMCjMI75M1A2tKUQC";
        let query = BrowseQuery::Search {
            query: "Requested title".into(),
            offset: 2,
            limit: 2,
        };
        let mut context = librespot::protocol::context::Context::new();
        let mut part = librespot::protocol::context_page::ContextPage::new();
        for i in 0..5 {
            let mut track = librespot::protocol::context_track::ContextTrack::new();
            track.uri = Some(uri.into());
            track.uid = Some(format!("occurrence-{i}"));
            part.tracks.push(track);
        }
        context.pages.push(part);
        let page =
            context_page(&context, 2, 2).unwrap_or_else(|_| panic!("observed search window"));
        let revision = browse_revision(&query, &page);
        assert_eq!(
            collection_position(&query, &page, &context, Some(&revision), 1, uri).ok(),
            Some(3)
        );
        assert!(collection_position(&query, &page, &context, Some(&revision), 2, uri).is_err());
        context.pages[0].tracks.reverse();
        let changed = context_page(&context, 2, 2).unwrap_or_else(|_| panic!("fresh context"));
        assert!(collection_position(&query, &changed, &context, Some(&revision), 1, uri).is_err());
    }
    #[test]
    fn search_selection_never_falls_back_to_another_row_or_request_result() {
        let uri = "spotify:track:4uLU6hMCjMI75M1A2tKUQC";
        let query = BrowseQuery::Search {
            query: "Requested title".into(),
            offset: 0,
            limit: 2,
        };
        let mut context = librespot::protocol::context::Context::new();
        let mut part = librespot::protocol::context_page::ContextPage::new();
        for value in ["spotify:delimiter", uri] {
            let mut track = librespot::protocol::context_track::ContextTrack::new();
            track.uri = Some(value.into());
            part.tracks.push(track);
        }
        context.pages.push(part);
        let page =
            context_page(&context, 0, 2).unwrap_or_else(|_| panic!("filtered observed window"));
        let revision = browse_revision(&query, &page);
        assert_eq!(
            collection_position(&query, &page, &context, Some(&revision), 0, uri).ok(),
            Some(1)
        );
        let mut other = context.clone();
        other.pages[0].tracks[1].uri = Some("spotify:track:5uLU6hMCjMI75M1A2tKUQC".into());
        assert!(collection_position(&query, &page, &other, Some(&revision), 0, uri).is_err());
        assert!(collection_position(&query, &page, &context, None, 0, uri).is_err());
    }
    #[test]
    fn collection_identity_rejects_reordering_and_does_not_follow_label_cache_updates() {
        let query = BrowseQuery::Liked {
            offset: 0,
            limit: 20,
        };
        let mut page = json!({"revision":"upstream","items":[{"uri":"one","title":null},{"uri":"two","title":null}],"nextOffset":null,"complete":true});
        let original = browse_revision(&query, &page);
        page["items"][0]["title"] = json!("Observed label");
        assert_eq!(original, browse_revision(&query, &page));
        page["items"].as_array_mut().unwrap().reverse();
        assert_ne!(original, browse_revision(&query, &page));
        assert_ne!(
            browse_revision(&query, &page),
            browse_revision(
                &BrowseQuery::Liked {
                    offset: 20,
                    limit: 20
                },
                &page
            )
        );
    }
    #[test]
    fn cover_selection_uses_actual_bounded_spotify_images_only() {
        let cover = |side, suffix: &str| CoverImage {
            url: format!("https://i.scdn.co/image/{}", suffix.repeat(40)),
            width: side,
            height: side,
            size: Default::default(),
        };
        let mut bad = cover(640, "a");
        bad.url = "https://i.scdn.co.evil/image/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".into();
        let selected = select_artwork(&[bad, cover(2048, "b"), cover(320, "c"), cover(640, "d")])
            .expect("actual bounded cover");
        assert_eq!(selected.width, 640);
        assert_eq!(
            selected.url,
            format!("https://i.scdn.co/image/{}", "d".repeat(40))
        );
        assert!(select_artwork(&[cover(2048, "a")]).is_none());
    }
    #[test]
    fn browse_rejects_unsafe_uris_and_windows() {
        assert!(
            BrowseQuery::Playlist {
                uri: "https://localhost/private".into(),
                offset: 0,
                limit: 10
            }
            .validate()
            .is_err()
        );
        assert!(
            BrowseQuery::Search {
                query: "bad\nquery".into(),
                offset: 0,
                limit: 10
            }
            .validate()
            .is_err()
        );
        assert!(
            BrowseQuery::Liked {
                offset: 0,
                limit: 41
            }
            .validate()
            .is_err()
        );
        assert_eq!(escape_query("a/b? ü"), "a%2Fb%3F%20%C3%BC");
    }
    #[test]
    fn unavailable_media_never_leaks_an_old_track() {
        let mut state = PlaybackState::unavailable(1);
        state.track_id = Some("old".into());
        assert_eq!(
            media_payload("new-session", &state, None)["track"],
            Value::Null
        );
    }
    #[test]
    fn page_pointers_cannot_target_arbitrary_origins() {
        assert!(safe_page_uri("hm://collection/v1/page?offset=20"));
        for value in [
            "https://evil.example/private",
            "hm://user@evil",
            "hm://../secret",
            "hm://valid\nheader",
        ] {
            assert!(!safe_page_uri(value));
        }
    }
    #[tokio::test]
    async fn absent_or_changed_session_never_attempts_account_access() {
        let sessions = Arc::new(Mutex::new(None));
        let browser = Browser::new(Arc::clone(&sessions));
        let query = BrowseQuery::Liked {
            offset: 0,
            limit: 20,
        };
        assert!(browser.read("old-session", query.clone()).await.is_err());
        *sessions.lock().await = Some(BrowseSession {
            id: "new-session".into(),
            session: Session::new(librespot::core::SessionConfig::default(), None),
        });
        assert!(browser.read("old-session", query).await.is_err());
        let lease = browser.slots.acquire_many(2).await.expect("local slots");
        assert!(
            browser
                .read(
                    "new-session",
                    BrowseQuery::Playlists {
                        offset: 0,
                        limit: 20
                    }
                )
                .await
                .is_err()
        );
        drop(lease);
    }
    #[test]
    fn rootlist_offset_mismatch_is_not_a_different_page() {
        let mut list = librespot::protocol::playlist4_external::SelectedListContent::new();
        list.contents =
            protobuf::MessageField::some(librespot::protocol::playlist4_external::ListItems::new());
        assert!(playlist_page(&list, 20, 20, true).is_err());
    }
    #[test]
    fn incomplete_context_is_not_a_complete_library() {
        let mut context = librespot::protocol::context::Context::new();
        let mut page = librespot::protocol::context_page::ContextPage::new();
        page.next_page_url = Some("hm://unresolved".into());
        context.pages.push(page);
        let result = context_page(&context, 0, 10).unwrap_or_else(|_| panic!("valid context"));
        assert_eq!(result["complete"], false);
        assert_eq!(result["nextOffset"], Value::Null);
    }
    #[test]
    fn rootlist_retains_legacy_playlists_details_and_paginates_over_folder_markers() {
        use librespot::protocol::playlist4_external::*;
        let mut list=SelectedListContent::new(); list.revision=Some(vec![1,2]);list.length=Some(3);
        let mut contents=ListItems::new();contents.pos=Some(0);contents.truncated=Some(true);
        let mut folder=Item::new();folder.uri=Some("spotify:start-group:folder".into());contents.items.push(folder);
        let mut item=Item::new();item.uri=Some("spotify:user:owner:playlist:4uLU6hMCjMI75M1A2tKUQC".into());contents.items.push(item);
        contents.meta_items.push(MetaItem::new());
        let mut meta=MetaItem::new();meta.length=Some(0);meta.owner_username=Some("Actual owner".into());
        let mut attributes=ListAttributes::new();attributes.name=Some("Actual playlist".into());attributes.description=Some("A <a href='x'>real</a> description".into());attributes.picture=Some(vec![0xab;20]);attributes.collaborative=Some(false);
        meta.attributes=protobuf::MessageField::some(attributes);contents.meta_items.push(meta);list.contents=protobuf::MessageField::some(contents);
        let page=playlist_page(&list,0,2,true).unwrap_or_else(|_|panic!("valid decorated rootlist"));
        assert_eq!(page["items"].as_array().map(Vec::len),Some(1));assert_eq!(page["items"][0]["uri"],"spotify:playlist:4uLU6hMCjMI75M1A2tKUQC");
        assert_eq!(page["items"][0]["playlist"]["trackCount"],0);assert_eq!(page["items"][0]["playlist"]["description"],"A real description");
        assert_eq!(page["nextOffset"],2);assert_eq!(page["complete"],false);assert_eq!(page["collectionRevision"],"0102");
        assert_eq!(page["items"][0]["playlist"]["artwork"]["url"],format!("https://i.scdn.co/image/{}","ab".repeat(20)));
    }
    #[test]
    fn playlist_covers_never_accept_arbitrary_hosts_redirect_queries_or_oversized_descriptors() {
        assert!(playlist_image_url(&format!("https://mosaic.scdn.co/300/{}","ab".repeat(80))).is_some());
        for url in ["https://evil/image/abcd", "https://i.scdn.co@evil/image/abcd", "https://mosaic.scdn.co/2048/abcd", "https://i.scdn.co/image/abcd?key=secret"] {
            assert!(playlist_image_url(url).is_none());
        }
        assert!(canonical_playlist_uri("spotify:start-group:folder").is_none());
        assert!(canonical_playlist_uri("spotify:album:4uLU6hMCjMI75M1A2tKUQC").is_none());
    }

}
