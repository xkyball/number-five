package com.no5.helm.network

import com.no5.domain.AudioCommandStatus
import org.json.JSONObject

/** A 202 admits a goal. Only the retained APPLIED state proves its effect. */
internal fun audioCommandResult(value: String, requestId: String, server: String): Pair<String, AudioV1Ack?> = try {
    val receipt = JSONObject(value)
    require(receipt.getInt("version") == 1 && receipt.getString("kind") == "audio-command-result")
    require(receipt.getString("requestId") == requestId)
    if(receipt.has("sessionId"))require(receipt.getString("sessionId")==requestId.substringBeforeLast(':'))
    if(receipt.has("serverSessionId"))require(receipt.getString("serverSessionId")==server)
    val id = receipt.getString("commandId").also { require(it.matches(Regex("[A-Za-z0-9_-]{1,64}"))) }
    val status = receipt.getString("status")
    if (status in setOf("ACCEPTED", "RUNNING")) id to null else {
    if (status != "APPLIED" && status != "REJECTED") throw PiProtocolException("Audio command $status; no replay")
    val snapshot = AudioV1Decoder.state(receipt.getJSONObject("state").toString())
    require(snapshot.serverSessionId == server)
    if(receipt.has("revision"))require(receipt.getLong("revision")==snapshot.state.revision)
    id to AudioV1Ack(requestId.substringBeforeLast(':'), requestId,
        if (status == "APPLIED") AudioCommandStatus.VERIFIED else AudioCommandStatus.REJECTED,
        receipt.optString("message","AUDIO · $status"), snapshot)
    }
} catch(failure:Exception){throw PiProtocolException("Invalid or unconfirmed asynchronous audio result; no replay",failure)}
