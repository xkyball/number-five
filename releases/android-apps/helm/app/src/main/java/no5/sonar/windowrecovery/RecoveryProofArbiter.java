package no5.sonar.windowrecovery;
/** Main-thread lease shared by Window and component recovery. Cancellation
 * invalidates old callbacks; a late completion cannot release a newer request. */
public final class RecoveryProofArbiter {
    private long generation, active;
    public boolean busy(){return active!=0;}
    public long acquire(){if(busy())return 0;active=++generation;return active;}
    public boolean owns(long token){return token!=0&&active==token;}
    public void release(long token){if(owns(token))active=0;}
    public void cancel(){active=0;generation++;}
}
