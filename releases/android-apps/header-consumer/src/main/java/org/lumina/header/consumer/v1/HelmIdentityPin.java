package org.lumina.header.consumer.v1;

/** Facts come from the privileged package/APK adapter, never a peer's declaration. */
public final class HelmIdentityPin {
    public static final String PACKAGE="com.no5.helm";
    public final int uid;public final String signerSha256,apkSha256;
    public HelmIdentityPin(int uid,String signerSha256,String apkSha256){
        if(uid<=0||uid>=100000)throw new IllegalArgumentException("HELM user0 UID");this.uid=uid;
        this.signerSha256=sha(signerSha256);this.apkSha256=sha(apkSha256);
    }
    public static final class Facts {
        public final String packageName,sharedUserId,apkSha256;public final int applicationUid;
        private final String[] packagesForUid,currentSignerHashes;
        public Facts(String packageName,int applicationUid,String[] packagesForUid,String sharedUserId,String[] currentSignerHashes,String apkSha256){
            this.packageName=packageName;this.applicationUid=applicationUid;
            this.packagesForUid=packagesForUid==null?null:packagesForUid.clone();this.sharedUserId=sharedUserId;
            this.currentSignerHashes=currentSignerHashes==null?null:currentSignerHashes.clone();this.apkSha256=apkSha256;
        }
    }
    public boolean matches(Facts f){return f!=null&&f.applicationUid==uid&&PACKAGE.equals(f.packageName)&&f.sharedUserId==null&&
        f.packagesForUid!=null&&f.packagesForUid.length==1&&PACKAGE.equals(f.packagesForUid[0])&&
        f.currentSignerHashes!=null&&f.currentSignerHashes.length==1&&signerSha256.equals(f.currentSignerHashes[0])&&apkSha256.equals(f.apkSha256);}
    private static String sha(String s){if(s==null||!s.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("pin sha256");return s;}
}
