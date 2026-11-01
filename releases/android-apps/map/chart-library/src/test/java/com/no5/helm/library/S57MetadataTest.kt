package com.no5.helm.library

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files

/** Identification-only fixture; never claimed to contain native-valid geometry or Inland portrayal. */
internal object S57TestBytes {
    fun cell(name: String = "1W7UH010.000", edition: Int = 19, update: Int = 0,
        purpose: Int = if (update == 0) 1 else 2, usage: Int = 7, product: Int = 10,
        productEdition: String = "2.4", profile: Int = purpose, agency: Int = 7984,
        description: String = "", standard: String = "03.1"): ByteArray {
        val field = ByteArrayOutputStream().apply {
            write(byteArrayOf(10, 1, 0, 0, 0, purpose.toByte(), usage.toByte()))
            fun text(value: String) { write(value.toByteArray(Charsets.US_ASCII)); write(31) }
            text(name); text(edition.toString()); text(update.toString())
            write("2026071420260714$standard".toByteArray(Charsets.US_ASCII)); write(product)
            text(description); text(productEdition)
            write(profile); write(agency and 255); write((agency ushr 8) and 255)
            text(""); write(30)
        }.toByteArray()
        val base = 35 // one four-character tag, three-digit length/position, directory terminator.
        val leader = "00000 D     00000   3304".toByteArray(Charsets.US_ASCII)
        require(leader.size == 24)
        "%05d".format(base + field.size).toByteArray().copyInto(leader, 0)
        "%05d".format(base).toByteArray().copyInto(leader, 12)
        return leader + ("DSID%03d000".format(field.size)).toByteArray() + byteArrayOf(30) + field
    }
}

class S57MetadataTest {
    private fun read(bytes: ByteArray): S57Metadata {
        val file = Files.createTempFile("s57-identification", ".000").toFile()
        return try { file.writeBytes(bytes); S57Identification.read(file) } finally { file.delete() }
    }
    private fun rejected(bytes: ByteArray) {
        assertThrows(RuntimeException::class.java) { read(bytes) }
    }
    @Test fun wsv24IdentityIncludesProducerProductAndRiverUsage() {
        val value = read(S57TestBytes.cell())
        assertEquals("1W7UH010.000", value.name); assertEquals(19, value.edition)
        assertEquals(S57Profiles.WSV, value.provider); assertEquals(7984, value.producingAgency)
        assertEquals(10, value.productSpecification); assertEquals("2.4", value.productEdition)
        assertEquals(7, value.usage); assertEquals(1, value.applicationProfile)
    }
    @Test fun noaaProfileRemainsDistinctAndRequiresItsActualProducer() {
        val value = read(S57TestBytes.cell("US5BOSCC.000", 5, usage = 5, product = 1, productEdition = "2.0", agency = 550))
        assertEquals(S57Profiles.NOAA, value.provider)
        rejected(S57TestBytes.cell("US5BOSCC.000", 5, usage = 5, product = 1, productEdition = "2.0", agency = 7984))
        rejected(S57TestBytes.cell("DE5UH010.000", usage = 5, product = 1, productEdition = "2.0", agency = 550))
    }
    @Test fun inlandIdentityCannotBeForgedByRenamingACountryPrefix() {
        for (bytes in listOf(S57TestBytes.cell(agency = 550), S57TestBytes.cell(product = 1),
            S57TestBytes.cell(productEdition = "2.3"), S57TestBytes.cell(description = "custom"),
            S57TestBytes.cell("WW7UH010.000"), S57TestBytes.cell("1W6UH010.000", usage = 6),
            S57TestBytes.cell("1w7uh010.000"), S57TestBytes.cell(usage = 8))) rejected(bytes)
    }
    @Test fun wsvHarbourAndBerthingHaveMatchingNameAndUsage() {
        for (usage in 7..9) assertEquals(usage, read(S57TestBytes.cell("1W${usage}UH010.000", usage = usage)).usage)
    }
    @Test fun revisionAndCancellationKeepTheSameStrictWsvProfile() {
        assertEquals(2, read(S57TestBytes.cell("1W7UH010.001", update = 1)).applicationProfile)
        assertEquals(0, read(S57TestBytes.cell("1W7UH010.001", edition = 0, update = 1)).edition)
        rejected(S57TestBytes.cell(edition = 0))
        rejected(S57TestBytes.cell("1W7UH010.001", update = 1, profile = 1))
        rejected(S57TestBytes.cell("1W7UH010.001", update = 1, purpose = 1))
        rejected(S57TestBytes.cell("1W7UH010.002", update = 1))
    }
    @Test fun malformedTruncatedDuplicateAndOtherS57EditionAreRejected() {
        val good = S57TestBytes.cell()
        rejected(good.copyOf(good.size - 1)); rejected(good + good)
        rejected(S57TestBytes.cell(standard = "03.0"))
        rejected(good.clone().also { it[34] = 0 })
        rejected(good.clone().also { it[20] = '0'.code.toByte() })
    }
}
