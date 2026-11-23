package org.amnezia.awg.util;
import org.junit.Test;
import static org.junit.Assert.*;
public class ChannelReleaseTest {
    private ChannelRelease release(String channel, String version, long code) {
        return new ChannelRelease(channel, version, version, code,
            ChannelRelease.RELEASES + "/download/" + version + "/AmneziaWG-MN-" + version + "-arm64.apk", "a".repeat(64), "notes");
    }
    @Test public void stableNeverReceivesBeta() {
        ChannelRelease s=release("stable","1.2.3",25), b=release("beta","1.3.0-beta.1",24);
        assertSame(s,ChannelRelease.select("stable","1.2.2",23,s,b));
        assertNull(ChannelRelease.select("stable","1.3.0-beta.1",24,s,b));
    }
    @Test public void betaGetsNextBetaThenFinal() {
        ChannelRelease b=release("beta","1.3.0-beta.2",25), s=release("stable","1.2.2",23);
        assertSame(b,ChannelRelease.select("beta","1.3.0-beta.1",24,s,b));
        s=release("stable","1.3.0",26);
        assertSame(s,ChannelRelease.select("beta","1.3.0-beta.2",25,s,b));
        assertSame(s,ChannelRelease.select("stable","1.3.0-beta.2",25,s,b));
    }
    @Test public void hotfixIsNotASemanticDowngradeAndCodeMustIncrease() {
        ChannelRelease s=release("stable","1.2.3",26), b=release("beta","1.3.0-beta.2",25);
        assertNull(ChannelRelease.select("beta","1.3.0-beta.2",25,s,b));
        assertNull(ChannelRelease.select("beta","1.2.3",26,s,b));
    }
    @Test public void betaWithoutBetaReleaseCanUseStable() {
        ChannelRelease s=release("stable","1.3.0",26);
        assertSame(s,ChannelRelease.select("beta","1.2.2",23,s,null));
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsForeignUrl() {
        new ChannelRelease("stable","1.3.0","1.3.0",24,"https://evil.invalid/apk","a".repeat(64),"");
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsMislabelledBeta() { release("stable","1.3.0-beta.1",24); }
    @Test(expected=IllegalArgumentException.class) public void rejectsMalformedBeta() { release("beta","1.3.0-beta.01",24); }
    @Test public void semverOrdersNumericIdentifiers() {
        assertTrue(UpdateVersion.isNewer("1.3.0-beta.10","1.3.0-beta.2"));
        assertTrue(UpdateVersion.isNewer("1.3.0","1.3.0-beta.100"));
        assertFalse(UpdateVersion.isNewer("1.3.0-beta.1","1.3.0"));
    }
    @Test public void staleChannelGenerationCannotCommitOrBeReused() {
        ChannelGate gate=new ChannelGate("beta",3); ChannelGate.Snapshot old=gate.snapshot();
        gate.change("stable");
        assertFalse(gate.commit(old, () -> fail("Must discard old APK")));
        gate.change("beta"); // ABA: changing back must not resurrect the old operation.
        assertFalse(gate.current(old));
        assertTrue(gate.commit(gate.snapshot(), () -> { }));
    }
    @Test public void allowedCachedStableSurvivesBetaOptInButBetaDoesNotSurviveOptOut() {
        assertTrue(release("stable","1.3.0",26).allowed("beta"));
        assertFalse(release("beta","1.4.0-beta.1",27).allowed("stable"));
    }
}
