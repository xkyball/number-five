package org.lumina.header.consumer.v1;

/** Authentication boundary. Production probes must read kernel credentials, never wire fields. */
public final class HeaderPeerAdmission {
    public enum Role { ROOT_CONSUMER, HELM_PRODUCER }
    public interface KernelPeerProbe { int uid(); int pid(); int gid(); }
    /** Trusted package/build guard: singleton HELM UID, user0, no sharedUID, pinned signer/APK/build. */
    public interface PinnedHelmVerifier { boolean verifiedInstalledHelm(int uid); }
    public final Role role;public final int uid,pid,gid;
    private HeaderPeerAdmission(Role role,int uid,int pid,int gid){this.role=role;this.uid=uid;this.pid=pid;this.gid=gid;}
    public static HeaderPeerAdmission root(KernelPeerProbe p){
        if(p==null)throw new SecurityException("kernel peer missing");int uid=p.uid(),pid=p.pid(),gid=p.gid();
        if(uid!=0||pid<=0||gid<0)throw new SecurityException("UID0 peer required");
        return new HeaderPeerAdmission(Role.ROOT_CONSUMER,uid,pid,gid);
    }
    public static HeaderPeerAdmission helm(KernelPeerProbe p,int expectedUid,PinnedHelmVerifier verifier){
        if(p==null)throw new SecurityException("kernel peer missing");int uid=p.uid(),pid=p.pid(),gid=p.gid();
        if(expectedUid<=0||expectedUid>=100000||uid!=expectedUid||pid<=0||gid<0||
            verifier==null||!verifier.verifiedInstalledHelm(expectedUid))throw new SecurityException("pinned HELM peer required");
        return new HeaderPeerAdmission(Role.HELM_PRODUCER,uid,pid,gid);
    }
}
