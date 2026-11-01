package org.lumina.header.consumer.v1;

import android.content.pm.*;
import android.os.Looper;
import java.io.*;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import static org.lumina.header.consumer.v1.HeaderConsumerContract.*;

/** Concrete API29 package guard, compile-only here. The reviewed pinned APK includes the build ID. */
public final class AndroidPinnedHelmVerifier implements HeaderPeerAdmission.PinnedHelmVerifier {
    private final PackageManager pm;private final HelmIdentityPin pin;private final Clock clock;
    private static final long MAX_APK_BYTES=256L*1024*1024, CHECK_MS=1500;
    public AndroidPinnedHelmVerifier(PackageManager pm,HelmIdentityPin pin,Clock clock){this.pm=req(pm);this.pin=req(pin);this.clock=req(clock);}
    public boolean verifiedInstalledHelm(int uid){
        if(uid!=pin.uid||(Looper.getMainLooper()!=null&&Looper.myLooper()==Looper.getMainLooper()))return false;
        try {
            long start=clock.elapsedRealtimeMs();PackageInfo before=info();File file=apk(before);String path=file.getCanonicalPath();
            long length=file.length(),modified=file.lastModified();if(length<=0||length>MAX_APK_BYTES)return false;
            String[] packages=pm.getPackagesForUid(uid),signers=signers(before);String hash=hashFile(file,start);
            PackageInfo after=info();File current=apk(after);
            if(!path.equals(current.getCanonicalPath())||length!=current.length()||modified!=current.lastModified()||
                !Arrays.equals(packages,pm.getPackagesForUid(uid))||!Arrays.equals(signers,signers(after))||
                before.applicationInfo.uid!=after.applicationInfo.uid||!before.packageName.equals(after.packageName)||
                !java.util.Objects.equals(before.sharedUserId,after.sharedUserId))return false;
            if(clock.elapsedRealtimeMs()<start||clock.elapsedRealtimeMs()>=deadline(start,CHECK_MS))return false;
            return pin.matches(new HelmIdentityPin.Facts(after.packageName,after.applicationInfo.uid,packages,after.sharedUserId,signers,hash));
        } catch(IOException|GeneralSecurityException|PackageManager.NameNotFoundException|RuntimeException e){return false;}
    }
    private PackageInfo info()throws PackageManager.NameNotFoundException {return pm.getPackageInfo(HelmIdentityPin.PACKAGE,PackageManager.GET_SIGNING_CERTIFICATES);}
    private static File apk(PackageInfo p)throws IOException {if(p.applicationInfo==null||p.applicationInfo.sourceDir==null)throw new IOException("APK metadata");return new File(p.applicationInfo.sourceDir);}
    private static String[] signers(PackageInfo p)throws GeneralSecurityException {
        if(p.signingInfo==null)return null;Signature[] ss=p.signingInfo.getApkContentsSigners();if(ss==null)return null;
        String[] hashes=new String[ss.length];for(int i=0;i<ss.length;i++)hashes[i]=hex(MessageDigest.getInstance("SHA-256").digest(ss[i].toByteArray()));return hashes;
    }
    private String hashFile(File file,long start)throws IOException,GeneralSecurityException {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[32768];long count=0;
        try(InputStream stream=new FileInputStream(file)){for(int n;(n=stream.read(buffer))!=-1;){long now=clock.elapsedRealtimeMs();
            if(now<start||now>=deadline(start,CHECK_MS)||(count+=n)>MAX_APK_BYTES)throw new IOException("APK guard bound");digest.update(buffer,0,n);}}
        return hex(digest.digest());
    }
    private static String hex(byte[] bytes){StringBuilder b=new StringBuilder(bytes.length*2);for(byte v:bytes){b.append(Character.forDigit((v>>>4)&15,16));b.append(Character.forDigit(v&15,16));}return b.toString();}
    // PM/APK checks prove installed identity, not the peer's exact loaded instructions or a Binder/kernel runtime.
    // One bounded off-Main owner must run this. Slow/blocked PM/file calls are not hard-cancelled by Java clocks.
}
