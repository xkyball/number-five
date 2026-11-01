package no5.sonar.windowrecovery;

/** Host-JVM admission tests only; the native fixture is a separate test. */
public final class WindowDrawPolicyTest {
    private static int checks;
    private static void expect(boolean value, boolean expected, String name) {
        checks++; if (value != expected) throw new AssertionError(name);
    }
    private static WindowDrawPolicy active() {
        WindowDrawPolicy p = new WindowDrawPolicy();
        p.progress("own-session1", 1L, true, 0, 0); p.drawn(0);
        p.progress("own-session1", 2L, true, 0, 500);
        return p;
    }
    public static void main(String[] args) {
        WindowDrawPolicy p = active();
        expect(p.needsProbe(999), false, "no early draw-timeout");
        expect(p.needsProbe(1000), true, "fresh progression and missing draw");
        expect(p.needsProbe(2500), true, "freshness boundary");
        expect(p.needsProbe(2501), false, "stale progression");
        expect(p.needsProbe(499), false, "reversed monotonic observation is not repaired");
        p.drawn(999); expect(p.needsProbe(1000), false, "healthy draw");
        p = active(); p.pause(); expect(p.needsProbe(1000), false, "pause");
        p = active(); p.progress("own-session1", 3L, false, 0, 900);
        expect(p.needsProbe(1100), false, "not own/focused/unfrozen/live");
        p = active(); p.progress("own-session1", 3L, true, 2001, 900);
        expect(p.needsProbe(1100), false, "original too old");
        p = active(); p.progress("own-session1", 3L, true, -1, 900);
        expect(p.needsProbe(1100), false, "unknown/negative original age");
        p = active(); p.progress("own-session2", 3L, true, 0, 900);
        expect(p.needsProbe(1100), false, "new source needs new draw and progression");
        p.drawn(950); p.progress("own-session2", 4L, true, 0, 1000);
        expect(p.needsProbe(1950), true, "new source independently admitted");
        p = active(); p.progress("own-session1", 2L, true, 0, 2300);
        expect(p.needsProbe(2501), false, "same ping never freshens progression");
        p = active(); p.progress("own-session1", null, true, 0, 900);
        expect(p.needsProbe(1100), false, "missing ping");
        p = active(); p.progress(null, 3L, true, 0, 900);
        expect(p.needsProbe(1100), false, "missing source");
        p = new WindowDrawPolicy(); p.progress("own", 1L, true, 0, 0); p.drawn(0);
        expect(p.needsProbe(1000), false, "single sample not advancing");
        p = active(); p.pause(); p.progress("own-session1", 3L, true, 0, 900);
        expect(p.needsProbe(1100), false, "resume cannot use old baseline");
        p.drawn(1000); p.progress("own-session1", 4L, true, 0, 1100);
        expect(p.needsProbe(2000), true, "resume with new draw and progression");
        p = active(); p.progress("own-session1", 3L, true, 1500, 500);
        expect(p.needsProbe(1000), true, "original age plus local wait at boundary");
        expect(p.needsProbe(1001), false, "original age plus local wait too old");
        System.out.println("WindowDrawPolicy: " + checks + " checks PASS");
    }
}
