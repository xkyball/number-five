package no5.sonar.windowrecovery;
import org.junit.Test;import static org.junit.Assert.*;
public class RecoveryProofArbiterTest {
 @Test public void componentFirstBlocksWindowUntilInspectionCompletes(){RecoveryProofArbiter a=new RecoveryProofArbiter();long c=a.acquire();assertTrue(a.owns(c));assertEquals(0,a.acquire());a.release(c);assertTrue(a.acquire()>c);}
 @Test public void windowFirstBlocksComponentDuringProofAndProbe(){RecoveryProofArbiter a=new RecoveryProofArbiter();long w=a.acquire();assertTrue(a.busy());assertEquals(0,a.acquire());a.release(w);assertFalse(a.busy());}
 @Test public void lifecycleCancelAndLateCallbackCannotReleaseNewOwner(){RecoveryProofArbiter a=new RecoveryProofArbiter();long old=a.acquire();a.cancel();long current=a.acquire();a.release(old);assertTrue(a.owns(current));assertFalse(a.owns(old));a.release(current);assertFalse(a.busy());}
}
