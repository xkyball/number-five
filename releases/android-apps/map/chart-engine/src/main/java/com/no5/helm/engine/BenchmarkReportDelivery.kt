package com.no5.helm.engine

internal data class BenchmarkReportDelivery(val file: String, val warning: String = "")

/** Raw archive errors must not prevent saving the complete comparison as the latest report. */
internal fun saveBenchmarkReports(runId: String, report: String, rawReports: List<String>,
    saveRaw: (String) -> Unit, saveBenchmark: (String, String) -> String): BenchmarkReportDelivery {
    var failed = 0
    rawReports.forEach { raw -> try { saveRaw(raw) } catch (_: Exception) { failed++ } }
    val file = saveBenchmark(runId, report)
    return BenchmarkReportDelivery(file, if (failed == 0) "" else
        "Vergleichsreport gespeichert; $failed Rohgruppen konnten nicht zusätzlich archiviert werden. Sie sind im Vergleichsreport enthalten.")
}
