package com.no5.gateway

import android.content.Context
import android.content.pm.PackageManager

object LuminaControllerIdentity {
    fun trusted(context: Context): Boolean = try {
        val app = context.applicationContext
        val pm = app.packageManager
        val controller = pm.getPackageInfo(LuminaGatewayClient.CONTROLLER, PackageManager.GET_SIGNING_CERTIFICATES)
        val own = pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        controller.signingInfo?.hasMultipleSigners() == false && own.signingInfo?.hasMultipleSigners() == false &&
            pm.getPackagesForUid(controller.applicationInfo!!.uid)?.toSet() == setOf(LuminaGatewayClient.CONTROLLER) &&
            pm.checkSignatures(app.packageName, LuminaGatewayClient.CONTROLLER) == PackageManager.SIGNATURE_MATCH
    } catch (_: Exception) { false }
    fun isCallingController(context: Context, uid: Int): Boolean = trusted(context) && try {
        context.packageManager.getApplicationInfo(LuminaGatewayClient.CONTROLLER, 0).uid == uid
    } catch (_: Exception) { false }
}
