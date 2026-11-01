package com.no5.helm.ui

import com.no5.ui.No5Text
import com.no5.ui.UiLanguage

/** Translate application wording at the UI boundary; preserve unknown provider text. */
internal fun mapRouteNotice(message: String, language: String = UiLanguage.current()): String {
    val savePrefix = "Speichern fehlgeschlagen: "
    if (message.startsWith(savePrefix))
        return No5Text.text(language, "Speichern fehlgeschlagen: %s",
            mapRouteNotice(message.removePrefix(savePrefix), language))
    val unavailable = Regex("^Signal-K-Routen nicht verfügbar \\(HTTP ([0-9]{3})\\); Gateway-Service und Ressourcenanbieter prüfen\\.$")
        .matchEntire(message)
    if (unavailable != null)
        return No5Text.text(language,
            "Signal-K-Routen nicht verfügbar (HTTP %d); Gateway-Service und Ressourcenanbieter prüfen.",
            unavailable.groupValues[1].toInt())
    return No5Text.text(language, message)
}
