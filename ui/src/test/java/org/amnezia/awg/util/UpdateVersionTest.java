package org.amnezia.awg.util;
import org.junit.Test;
import static org.junit.Assert.*;
public class UpdateVersionTest {
    @Test public void comparesNumericComponents() {
        assertTrue(UpdateVersion.isNewer("v1.10.0","1.9.9"));
        assertTrue(UpdateVersion.isNewer("2.0.0","1.99.99"));
        assertFalse(UpdateVersion.isNewer("1.1.1","1.2.0"));
        assertFalse(UpdateVersion.isNewer("v1.2.0","1.2.0"));
    }
    @Test public void stablePromotesPrereleaseOnly() {
        assertTrue(UpdateVersion.isNewer("1.2.0","1.2.0-debug"));
        assertFalse(UpdateVersion.isNewer("1.2.0-rc1","1.2.0"));
    }
    @Test public void rejectsMalformedVersions() {
        assertFalse(UpdateVersion.isNewer("../../evil","1.2.0"));
        assertFalse(UpdateVersion.isNewer("1.2","1.2.0"));
    }
}
