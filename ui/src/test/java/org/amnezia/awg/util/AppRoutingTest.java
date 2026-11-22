package org.amnezia.awg.util;
import org.junit.Test;
import org.amnezia.awg.config.Config;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;
public class AppRoutingTest {
    private Config original() throws Exception {
        String text = "[Interface]\nPrivateKey = TFlmmEUC7V7VtiDYLKsbP5rySTKLIZq1yn8lMqK83wo=\nAddress = 192.0.2.2/32\nDNS = 192.0.2.1\nMTU = 1280\nJc = 4\nJmin = 40\nJmax = 70\n[Peer]\nPublicKey = vBN7qyUTb5lJtWYJ8LhbPio1Z4RcyBPGnqFBGn6O6Qg=\nAllowedIPs = 0.0.0.0/0\n";
        return Config.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void modesPreserveTunnelAndPeers() throws Exception {
        Config base = original();
        Config include = AppRouting.apply(base, 1, Arrays.asList("org.telegram.messenger"));
        assertEquals(Collections.singleton("org.telegram.messenger"), include.getInterface().getIncludedApplications());
        assertTrue(include.getInterface().getExcludedApplications().isEmpty());
        Config exclude = AppRouting.apply(include, 2, Arrays.asList("com.example.bank"));
        assertTrue(exclude.getInterface().getIncludedApplications().isEmpty());
        assertEquals(Collections.singleton("com.example.bank"), exclude.getInterface().getExcludedApplications());
        Config cleared = AppRouting.apply(exclude, 0, Collections.emptyList());
        assertEquals(base.toAwgQuickString(), cleared.toAwgQuickString());
        assertEquals(base.getPeers(), include.getPeers());
    }
    @Test(expected=IllegalArgumentException.class) public void emptyAllowlistCannotEnableVpnForAll() throws Exception {
        AppRouting.apply(original(),1,Collections.emptyList());
    }
    @Test(expected=IllegalArgumentException.class) public void preventsConfigLineInjection() throws Exception {
        AppRouting.apply(original(),2,Arrays.asList("com.example\nMTU = 42"));
    }
}
