package com.no5.helm.library

import java.io.File
import java.io.RandomAccessFile

/** Explicit supported producer profiles. A recognized identifier is not a portrayal/certification claim. */
object S57Profiles {
    const val NOAA = "noaa-s57"
    const val WSV = "wsv-inland-s57"
    fun supportedProvider(provider: String) = provider == NOAA || provider == WSV
    fun sourceLabel(provider: String): String = when (provider) {
        NOAA -> "NOAA S57 · https://www.charts.noaa.gov/ENCs/ENCs.shtml"
        WSV -> "WSV Inland ENC 2.4 · https://www.elwis.de/DE/Service/Inland-ENC-der-WSV/Inland-ENC-der-WSV-node"
        else -> error("Nicht unterstützter S57-Herausgeber")
    }
    internal fun identify(name: String, usage: Int, product: Int, description: String,
        productEdition: String, agency: Int): String {
        require(description.isEmpty()) { "Abweichende S57-Produktspezifikation wird nicht unterstützt" }
        return when {
            name.matches(Regex("US[1-6][A-Z0-9]{5}\\.[0-9]{3}")) && agency == 550 &&
                product == 1 && productEdition == "2.0" && usage in 1..6 && name[2].digitToInt() == usage -> NOAA
            name.matches(Regex("1W[7-9][A-Z0-9]{5}\\.[0-9]{3}")) && agency == 7984 &&
                product == 10 && productEdition == "2.4" && usage in 7..9 && name[2].digitToInt() == usage -> WSV
            else -> error("Nur geprüfte NOAA-S57- und WSV-Inland-ENC-2.4-Profile werden unterstützt: $name")
        }
    }
}

/** Strict S57 3.1 (ISO 8211) identification. Native geometry ingestion is still required before publication. */
data class S57Metadata(val name: String, val edition: Int, val update: Int, val issueDate: String, val exchangePurpose: Int,
    val provider: String, val producingAgency: Int, val usage: Int, val productSpecification: Int,
    val productEdition: String, val applicationProfile: Int)
object S57Identification {
    fun read(file: File): S57Metadata = read(file) {}
    fun read(file: File, checkpoint: (Long) -> Unit): S57Metadata {
        RandomAccessFile(file, "r").use { input ->
            require(input.length() in 25..(128L * 1024 * 1024)) { "S57-Dateigröße außerhalb des Limits: ${file.name}" }
            checkpoint(0)
            var metadata: S57Metadata? = null
            var records = 0
            while (input.filePointer < input.length()) {
                checkpoint(input.filePointer)
                require(++records <= 2_000_000 && input.length() - input.filePointer >= 24) { "Abgeschnittener S57-Datensatz" }
                val leader = ByteArray(24).also(input::readFully)
                fun number(offset: Int, count: Int): Int {
                    val text = String(leader, offset, count, Charsets.US_ASCII)
                    require(text.all(Char::isDigit)) { "Ungültiger ISO-8211-Kopf" }
                    return text.toInt()
                }
                val length = number(0, 5)
                val base = number(12, 5)
                val lengthWidth = number(20, 1); val positionWidth = number(21, 1); val tagWidth = number(23, 1)
                val width = lengthWidth + positionWidth + tagWidth
                require(length in 25..99999 && base in 25..length && lengthWidth > 0 && positionWidth > 0 && tagWidth > 0 &&
                    (base - 25) % width == 0 && length - 24 <= input.length() - input.filePointer) { "Ungültige ISO-8211-Datensatzlänge" }
                val record = ByteArray(length).also { leader.copyInto(it); input.readFully(it, 24, length - 24) }
                require(record[base - 1] == 0x1e.toByte()) { "ISO-8211-Verzeichnisabschluss fehlt" }
                for (offset in 24 until base - 1 step width) {
                    val tag = String(record, offset, tagWidth, Charsets.US_ASCII)
                    val fieldLength = String(record, offset + tagWidth, lengthWidth, Charsets.US_ASCII).toIntOrNull()
                    val fieldPosition = String(record, offset + tagWidth + lengthWidth, positionWidth, Charsets.US_ASCII).toIntOrNull()
                    require(fieldLength != null && fieldPosition != null && fieldLength > 0 && fieldPosition >= 0 &&
                        fieldPosition.toLong() + fieldLength <= length - base) { "S57-Feld außerhalb des Datensatzes" }
                    val field = record.copyOfRange(base + fieldPosition, base + fieldPosition + fieldLength)
                    // Lexical level 2 national text uses a two-byte field terminator (1e 00).
                    require(field.last() == 0x1e.toByte() || field.size >= 2 && field[field.lastIndex - 1] == 0x1e.toByte() && field.last() == 0.toByte()) { "S57-Feldabschluss fehlt: ${file.name}" }
                    if (tag == "DSID" && leader[6] == 'D'.code.toByte()) {
                        require(metadata == null && field.size > 30 && field[0] == 10.toByte()) { "Ungültiges oder doppeltes DSID" }
                        // S57 3.1 DSID: RCNM b11, RCID b14, EXPP b11, INTU b11, then DSNM/EDTN/UPDN A.
                        var cursor = 7
                        fun text(): String {
                            val end = (cursor until field.size).firstOrNull { field[it] == 0x1f.toByte() } ?: error("DSID-Trenner fehlt")
                            require((cursor until end).all { field[it].toInt() in 0x20..0x7e }) { "Ungültiger S57-Identifikationstext" }
                            return String(field, cursor, end - cursor, Charsets.US_ASCII).also { cursor = end + 1 }
                        }
                        val name = text(); val edition = text().toIntOrNull(); val update = text().toIntOrNull()
                        require(edition != null && edition >= 0 && update != null && update in 0..999) { "Ungültige S57-Edition oder Updatenummer: ${file.name}" }
                        require(cursor + 20 < field.size && String(field, cursor + 16, 4, Charsets.US_ASCII) == "03.1") { "Nur S57 3.1 wird unterstützt" }
                        val issue = String(field, cursor + 8, 8, Charsets.US_ASCII)
                        require(issue.matches(Regex("[0-9]{8}"))) { "Ungültiges S57-Ausgabedatum" }
                        val product = field[cursor + 20].toInt() and 0xff
                        cursor += 21
                        val description = text(); val productEdition = text()
                        require(cursor + 3 < field.size) { "S57-Profil oder Herausgeber fehlt" }
                        val profile = field[cursor].toInt() and 0xff
                        val agency = (field[cursor + 1].toInt() and 0xff) or ((field[cursor + 2].toInt() and 0xff) shl 8)
                        cursor += 3
                        text() // COMT is present even if empty; never used as a producer assertion.
                        require(cursor == field.lastIndex) { "Unerwartete S57-Identifikationsdaten" }
                        val purpose = field[5].toInt() and 0xff
                        val usage = field[6].toInt() and 0xff
                        require(purpose in 1..2 && profile == purpose &&
                            (update == 0 && purpose == 1 && edition > 0 || update > 0 && purpose == 2)) { "S57-Basis-/Updateprofil widersprüchlich" }
                        require(name.substringAfterLast('.', "").toIntOrNull() == update) { "S57-Dateiname und Updatenummer widersprüchlich" }
                        val provider = S57Profiles.identify(name, usage, product, description, productEdition, agency)
                        metadata = S57Metadata(name, edition, update, issue, purpose, provider, agency, usage, product, productEdition, profile)
                    }
                }
            }
            checkpoint(input.filePointer)
            return metadata ?: error("S57-DSID fehlt: ${file.name}")
        }
    }
}
