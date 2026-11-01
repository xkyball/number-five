package com.no5.ui;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ServiceInfo;
import android.os.Process;

/** Product signer pin accepts the OS-verified certificate lineage; debug is restricted to debug peers. */
public final class UiStateIdentity {
    private static final String RELEASE_SIGNER = "138eff84e534e5c8ae3cfc18adf57e80b2d7a7725d04acd1449ce3b9c373e6bd";
    private UiStateIdentity() {}
    public static boolean caller(Context context, int uid) {
        try {
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            return UiAdmissionPolicy.permits(uid, Process.myUid(), packages,
                    context.checkPermission(UiStateContract.PERMISSION, -1, uid) == PackageManager.PERMISSION_GRANTED,
                    packages != null && packages.length == 1 &&
                            context.getPackageManager().getApplicationInfo(packages[0], 0).uid == uid &&
                            approvedPair(context, packages[0]));
        } catch (Exception refused) { return false; }
    }
    public static int controller(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            ServiceInfo service = pm.getServiceInfo(UiStateContract.SERVICE, 0);
            PermissionInfo permission = pm.getPermissionInfo(UiStateContract.PERMISSION, 0);
            int uid = service.applicationInfo.uid;
            String[] packages = pm.getPackagesForUid(uid);
            if (!service.enabled || !service.applicationInfo.enabled || !service.exported ||
                    !UiStateContract.CONTROLLER.equals(service.packageName) ||
                    !UiStateContract.SERVICE.getClassName().equals(service.name) ||
                    !UiStateContract.PERMISSION.equals(service.permission) ||
                    !UiStateContract.CONTROLLER.equals(permission.packageName) ||
                    (permission.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE) != PermissionInfo.PROTECTION_SIGNATURE ||
                    uid / 100000 != Process.myUid() / 100000 || packages == null || packages.length != 1 ||
                    !UiStateContract.CONTROLLER.equals(packages[0]) || !approvedPair(context, UiStateContract.CONTROLLER))
                throw new SecurityException("UI_CONTROLLER_IDENTITY");
            return uid;
        } catch (Exception refused) { throw new SecurityException("UI_CONTROLLER_IDENTITY"); }
    }
    private static boolean approvedPair(Context context, String other) throws Exception {
        PackageManager pm = context.getPackageManager();
        PackageInfo own = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo peer = pm.getPackageInfo(other, PackageManager.GET_SIGNING_CERTIFICATES);
        if (own.signingInfo == null || peer.signingInfo == null || own.signingInfo.hasMultipleSigners() ||
                peer.signingInfo.hasMultipleSigners() ||
                pm.checkSignatures(context.getPackageName(), other) != PackageManager.SIGNATURE_MATCH) return false;
        if ((own.applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
                (peer.applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) return true;
        byte[] digest = new byte[32];
        for (int i = 0; i < digest.length; i++) digest[i] = (byte) Integer.parseInt(RELEASE_SIGNER.substring(i*2, i*2+2), 16);
        return pm.hasSigningCertificate(context.getPackageName(), digest, PackageManager.CERT_INPUT_SHA256) &&
                pm.hasSigningCertificate(other, digest, PackageManager.CERT_INPUT_SHA256);
    }
}
