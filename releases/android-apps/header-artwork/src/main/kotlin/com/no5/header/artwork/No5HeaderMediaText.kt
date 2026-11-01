package com.no5.header.artwork

import com.no5.ui.No5Text
import com.no5.ui.UiStateSnapshot

/** UI-only formatting; original media names and producer timestamps are never translated. */
data class No5HeaderMediaText(val title: String, val detail: String,
    val accessibilityDescription: String, val sourceGeneration: Long) {
    companion object {
        fun from(snapshot: UiStateSnapshot, nowElapsedMs: Long): No5HeaderMediaText {
            val language = snapshot.language
            val media = snapshot.media
            fun label(value: String) = No5Text.text(language, value)
            val available = snapshot.connected && media.available
            val title = if (available) media.title.ifBlank { label("Titel unbekannt") }
                else label("Keine Wiedergabe")
            val status = when {
                !available -> label("Nicht verfügbar")
                !media.isFresh(nowElapsedMs) -> label("Veraltet")
                media.playback == "PLAYING" -> label("Wiedergabe")
                media.playback == "PAUSED" -> label("Pausiert")
                media.playback == "STOPPED" -> label("Gestoppt")
                else -> label("Status unbekannt")
            }
            val detail = if (available && media.artist.isNotBlank()) "${media.artist} · $status" else status
            return No5HeaderMediaText(title, detail,
                No5Text.text(language, "%s. %s. Audio öffnen.", title, detail), media.sourceGeneration)
        }
    }
}
