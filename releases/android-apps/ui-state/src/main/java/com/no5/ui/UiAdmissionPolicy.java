package com.no5.ui;

/** Pure caller policy, evaluated before a request parcel is interpreted. */
public final class UiAdmissionPolicy {
    private UiAdmissionPolicy() {}
    public static boolean permits(int callerUid, int serviceUid, String[] packages,
                                  boolean signaturePermission, boolean approvedSigner) {
        if (callerUid < 0 || serviceUid < 0 || callerUid / 100000 != serviceUid / 100000 ||
                packages == null || packages.length != 1 || !signaturePermission || !approvedSigner) return false;
        String name = packages[0];
        return "com.no5.home".equals(name) || "com.no5.helm".equals(name) ||
                "com.no5.map".equals(name) || UiStateContract.CONTROLLER.equals(name);
    }
}
