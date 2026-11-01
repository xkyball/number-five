package com.no5.control

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Process

internal data class CallerIdentity(val uid: Int, val packageName: String, val userId: Int)
internal data class VerifiedService(val uid: Int)

internal object CallerAdmissionPolicy {
    fun verify(uid: Int, hostUser: Int, packages: List<String>, permissionGranted: Boolean,
               signatureMatches: Boolean): CallerIdentity {
        if (uid < 0 || uid / SystemControlContract.USER_UID_RANGE != hostUser ||
            packages.size != 1 || packages.single() !in SystemControlContract.CLIENT_PACKAGES ||
            !permissionGranted || !signatureMatches) throw SecurityException("Control caller refused")
        return CallerIdentity(uid, packages.single(), hostUser)
    }
}

internal class PackageCallerVerifier(context: Context) {
    private val app = context.applicationContext
    private val hostUser = Process.myUid() / SystemControlContract.USER_UID_RANGE

    init { require(app.packageName == SystemControlContract.HELM_PACKAGE) }

    fun verify(uid: Int): CallerIdentity {
        val packages = app.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        val signatureMatches = packages.size == 1 && app.packageManager.checkSignatures(
            packages.single(), SystemControlContract.HELM_PACKAGE) == PackageManager.SIGNATURE_MATCH
        return CallerAdmissionPolicy.verify(uid, hostUser, packages,
            app.checkPermission(SystemControlContract.PERMISSION, -1, uid) == PackageManager.PERMISSION_GRANTED,
            signatureMatches)
    }
}

internal class PackageServiceVerifier(context: Context) {
    private val app = context.applicationContext

    fun verify(): VerifiedService {
        if (app.packageName !in SystemControlContract.CLIENT_PACKAGES) throw SecurityException("Not a product client")
        val pm = app.packageManager
        val service = try {
            pm.getServiceInfo(SystemControlContract.SERVICE, 0)
        } catch (missing: PackageManager.NameNotFoundException) {
            throw SecurityException("Control service unavailable", missing)
        }
        val permission = try {
            pm.getPermissionInfo(SystemControlContract.PERMISSION, 0)
        } catch (missing: PackageManager.NameNotFoundException) {
            throw SecurityException("Control permission unavailable", missing)
        }
        val uid = service.applicationInfo.uid
        if (!service.enabled || !service.applicationInfo.enabled || !service.exported ||
            service.packageName != SystemControlContract.HELM_PACKAGE ||
            service.name != SystemControlContract.SERVICE.className ||
            service.permission != SystemControlContract.PERMISSION ||
            permission.packageName != SystemControlContract.HELM_PACKAGE ||
            (permission.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) != PermissionInfo.PROTECTION_SIGNATURE ||
            uid / SystemControlContract.USER_UID_RANGE != Process.myUid() / SystemControlContract.USER_UID_RANGE ||
            pm.getPackagesForUid(uid)?.toList() != listOf(SystemControlContract.HELM_PACKAGE) ||
            pm.checkSignatures(app.packageName, SystemControlContract.HELM_PACKAGE) != PackageManager.SIGNATURE_MATCH
        ) throw SecurityException("Control service identity refused")
        return VerifiedService(uid)
    }
}
