package com.no5.helm

import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.no5.domain.*
import com.no5.header.artwork.No5WorkspaceView
import com.no5.ui.*

private fun mediaOutcome(ctl:AudioNativeController)=when(ctl.mediaSelection.status){
    AudioMediaSelectionStatus.UNKNOWN->No5Text.ui("Ausgang unklar · keine Wiederholung")
    AudioMediaSelectionStatus.REJECTED->No5Text.ui("Auswahl abgewiesen · Liste aktualisieren")
    else->null
}
private fun mediaRow(ctl:AudioNativeController,index:Int,item:AudioQueueItem,queue:Boolean)=No5WorkspaceView.Row(
    item.title?:No5Text.ui("Titelinformationen fehlen"),item.artist,
    if(ctl.mediaRowPending(index,queue))No5Text.ui("Wird bestätigt …")else null,
    rawLabel=true,rawDetail=true,rawValue=true,enabled=ctl.canSelectMediaItem(index,item,queue),
    key=if(queue)"queue-row-$index"else"spotify-item-${item.uri}-$index",action={ctl.selectMedia(index,item,queue)})

/** The approved full workspace. Audio knobs retain the same controller; covered mixer hits are absent. */
@Composable internal fun AudioQueueOverlay(scale:Float,night:Boolean,ctl:AudioNativeController,close:()->Unit,modifier:Modifier){
    val manager=LocalContext.current.getSystemService(AccessibilityManager::class.java)
    DisposableEffect(manager,ctl){
        ctl.queueIdle.touchExploration=manager?.isTouchExplorationEnabled==true
        val listener=AccessibilityManager.TouchExplorationStateChangeListener{ctl.queueIdle.touchExploration=it}
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose{manager?.removeTouchExplorationStateChangeListener(listener);ctl.queueIdle.touchExploration=false;ctl.queueIdle.scrolling=false}
    }
    val items=ctl.mediaMetadata.queue?.next.orEmpty()
    val rows=items.mapIndexed{index,item->mediaRow(ctl,index,item,true)}+
        listOfNotNull(if(items.isEmpty())No5WorkspaceView.Row("Keine nächsten Titel verfügbar",key="queue-empty")else null,
            mediaOutcome(ctl)?.let{No5WorkspaceView.Row("Status",it,rawDetail=true,key="queue-outcome")})
    No5Workspace("Als Nächstes",No5Text.ui("%d nächste Titel · beobachteter Ausschnitt",items.size),rows,night,close,
        modifier.testTag("audio-queue-overlay"),rawSubtitle=true,liveSubtitle=true,
        onScroll={_,moving->ctl.queueIdle.scrolling=moving;ctl.queueIdle.activity()})
}

/** Actual playlists, likes, search and queue revisions remain owned by AudioNativeController. */
@Composable internal fun AudioMediaPanel(scale:Float,night:Boolean,ctl:AudioNativeController,close:()->Unit,modifier:Modifier){
    var page by remember(ctl){mutableStateOf("menu")}
    var details by remember(ctl){mutableStateOf(false)}
    val language=LocalNo5Language.current
    val query=ctl.musicQuery;val result=ctl.mediaBrowsing
    val matching=result.query==query
    val items=if(matching)result.page?.items.orEmpty()else emptyList()
    val playlists=if(matching)ctl.filteredPlaylists else emptyList()
    val receiver=ctl.mediaMetadata.receiverSessionId
    var firstArtwork by remember(query,ctl.playlistFilter,receiver){mutableIntStateOf(-1)}
    fun artwork(first:Int){if(query.kind==AudioBrowseKind.PLAYLISTS&&first!=firstArtwork){firstArtwork=first;ctl.requestPlaylistArtwork(playlists.drop(first).take(8))}}
    LaunchedEffect(page,query,playlists,receiver){if(page=="browse")artwork(0)}
    fun back(){if(details)details=false else if(page=="menu")close()else if(ctl.musicQuery.kind==AudioBrowseKind.PLAYLIST)ctl.browseMedia(AudioBrowseQuery(AudioBrowseKind.PLAYLISTS))else page="menu"}
    BackHandler{back()}
    val rows=if(page=="menu")listOf(
        No5WorkspaceView.Row("Als Nächstes",enabled=ctl.mediaAvailable,key="music-queue",action={ctl.openPanel("queue")}),
        No5WorkspaceView.Row("Playlists",enabled=ctl.mediaAvailable,key="music-playlists",action={page="browse";ctl.browseMedia(AudioBrowseQuery(AudioBrowseKind.PLAYLISTS))}),
        No5WorkspaceView.Row("Lieblingssongs",enabled=ctl.mediaAvailable,key="music-liked",action={page="browse";ctl.browseMedia(AudioBrowseQuery(AudioBrowseKind.LIKED))}),
        No5WorkspaceView.Row("Suche",enabled=ctl.mediaAvailable,key="music-search",action={page="browse";ctl.showMusicSearch()}))
    else buildList{
        when(query.kind){
            AudioBrowseKind.PLAYLISTS->{
                add(No5WorkspaceView.Row("Playlists filtern",value=ctl.playlistFilter,rawValue=true,key="playlist-filter",control="field",onValueChange={ctl.playlistFilter=it.take(200)}))
                playlists.forEach{item->
                    val p=item.playlist
                    val summary=listOfNotNull(p?.trackCount?.let{No5Text.ui("%d Titel",it)},p?.owner?.let{No5Text.ui("Von %s",it)},if(p?.collaborative==true)No5Text.ui("Gemeinsam")else null).joinToString(" · ")
                    add(No5WorkspaceView.Row(item.title?:No5Text.ui("Titelinformationen fehlen"),summary.takeIf{it.isNotEmpty()},enabled=ctl.mediaAvailable,
                        rawLabel=true,rawDetail=true,key="spotify-item-${item.uri}",action={ctl.openMusicPlaylist(item)}))
                }
            }
            AudioBrowseKind.SEARCH->add(No5WorkspaceView.Row("Titel oder Interpret",value=ctl.musicSearch,rawValue=true,key="spotify-search",control="field",onValueChange={ctl.musicSearch=it.take(200)}))
            AudioBrowseKind.PLAYLIST->if(ctl.musicPlaylistItem?.playlist!=null)add(No5WorkspaceView.Row("Playlistinformationen",key="playlist-information",action={details=true}))
            else->Unit
        }
        if(query.kind!=AudioBrowseKind.PLAYLISTS)items.forEachIndexed{index,item->add(mediaRow(ctl,index,item,false))}
        if(result.loading)add(No5WorkspaceView.Row("Wird geladen …",key="music-loading"))
        if(!ctl.mediaAvailable)add(No5WorkspaceView.Row("Musikverbindung nicht verfügbar",key="music-unavailable"))
        else if(!result.loading&&(if(query.kind==AudioBrowseKind.PLAYLISTS)playlists.isEmpty()else items.isEmpty()))add(No5WorkspaceView.Row(when{
            query.kind==AudioBrowseKind.SEARCH&&query.text.isNullOrBlank()->"Suche nach Titel oder Interpret"
            !matching||result.page==null->"Musikliste konnte nicht geladen werden"
            query.kind==AudioBrowseKind.PLAYLISTS&&ctl.playlistFilter.isNotBlank()->"Keine passenden Playlists"
            else->"Keine Titel verfügbar"},key="music-empty"))
        if(result.page?.unresolvedPages==true)add(No5WorkspaceView.Row("Weitere Titel sind derzeit nicht verfügbar",key="music-incomplete"))
        mediaOutcome(ctl)?.let{add(No5WorkspaceView.Row("Status",it,rawDetail=true,key="music-outcome"))}
    }
    val actions=if(page=="menu")emptyList()else buildList{
        when(query.kind){
            AudioBrowseKind.SEARCH->add(No5WorkspaceView.Action("Suchen",enabled=ctl.mediaAvailable&&ctl.musicSearch.isNotBlank()&&ctl.musicSearch.none(Char::isISOControl),key="music-search-submit",action={ctl.browseMedia(AudioBrowseQuery(AudioBrowseKind.SEARCH,text=ctl.musicSearch.trim()))}))
            AudioBrowseKind.PLAYLISTS->add(No5WorkspaceView.Action("Aktualisieren",enabled=ctl.mediaAvailable,key="music-refresh",action={ctl.browseMedia(AudioBrowseQuery(AudioBrowseKind.PLAYLISTS))}))
            AudioBrowseKind.PLAYLIST->if(query.offset==0){
                add(No5WorkspaceView.Action("Abspielen",enabled=ctl.canStartPlaylist,key="music-playlist-play",description="Playlist starten",action={ctl.startPlaylist(false)}))
                add(No5WorkspaceView.Action("Zufall",enabled=ctl.canStartPlaylist,key="music-playlist-shuffle",description="Mit Shuffle starten",action={ctl.startPlaylist(true)}))
            }
            else->Unit
        }
        if(query.kind!=AudioBrowseKind.PLAYLISTS){
            if(query.offset>0)add(No5WorkspaceView.Action("‹ Zurück",enabled=!result.loading,key="music-previous",action={ctl.browseMedia(query.copy(offset=(query.offset-query.limit).coerceAtLeast(0)))}))
            result.page?.takeIf{matching}?.nextOffset?.let{next->add(No5WorkspaceView.Action("Weiter ›",enabled=!result.loading,key="music-next",action={ctl.browseMedia(query.copy(offset=next))}))}
        }
    }
    val title=if(page=="menu")"Musik"else when(query.kind){AudioBrowseKind.PLAYLISTS->"Playlists";AudioBrowseKind.PLAYLIST->"Playlist";AudioBrowseKind.LIKED->"Lieblingssongs";AudioBrowseKind.SEARCH->"Suche"}
    val caption=if(page=="menu")ctl.state.media.title?:No5Text.ui("Kein Titel")else when(query.kind){
        AudioBrowseKind.PLAYLIST->ctl.musicPlaylistTitle
        AudioBrowseKind.PLAYLISTS->{val total=if(matching)result.page?.items?.size?:0 else 0
            if(ctl.playlistFilter.isNotBlank())No5Text.ui("%d Treffer · %d Playlists geladen",playlists.size,total)
            else if(result.loading)No5Text.ui("%d Playlists · weitere werden geladen",total)
            else if(result.page?.complete!=true)No5Text.ui("%d Playlists · Liste unvollständig",total)else No5Text.ui("%d Playlists",total)}
        else->No5Text.ui("%d Titel",items.size)
    }
    val saved=ctl.musicScroll[query]?: (0 to 0)
    No5Workspace(title,caption,rows,night,::back,modifier.testTag("audio-music-page"),showBack=page!="menu",actions=actions,
        rawSubtitle=true,liveSubtitle=true,stateKey=page to query,initialScrollY=if(page=="browse")saved.first*104+saved.second else 0,
        onScroll=if(page=="menu")null else {y,_->
            if(ctl.mediaMetadata.receiverSessionId==receiver){ctl.musicScroll[query]=y/104 to y%104;artwork(((y/104)-1).coerceAtLeast(0))}
        })
    if(details)ctl.musicPlaylistItem?.let{item->
        val p=item.playlist
        val picture=ctl.playlistBitmap(item)
        LaunchedEffect(item.playlist?.artwork?.id){ctl.requestPlaylistArtwork(listOf(item))}
        No5DetailsDialog("Playlistinformationen",night,{details=false},tag="playlist-information-dialog"){
            Row(horizontalArrangement=Arrangement.spacedBy((24*scale).dp)){
                if(picture!=null)Image(picture.asImageBitmap(),null,Modifier.size((104*scale).dp).testTag("playlist-cover-${item.uri}"),contentScale=ContentScale.Crop,
                    colorFilter=if(night)ColorFilter.tint(Color(0xffed0000),BlendMode.Modulate)else null,alpha=if(night).65f else 1f)
                Column{
                    Text(item.title?:No5Text.ui("Titelinformationen fehlen"))
                    p?.owner?.let{Text(No5Text.ui("Von %s",it))};p?.trackCount?.let{Text(No5Text.ui("%d Titel",it))}
                }
            }
            p?.description?.let{Text(it)}
            if(picture==null)Text(No5Text.ui("Kein Cover verfügbar"))
        }
    }
}
