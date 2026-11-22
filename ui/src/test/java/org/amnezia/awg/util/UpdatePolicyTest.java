package org.amnezia.awg.util;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;
public class UpdatePolicyTest {
    @Test public void acceptsNewerVersionWithSameIdentity() {
        assertTrue(UpdatePolicy.accepts("mn", 13, Arrays.asList("a", "b"), "mn", 14, Arrays.asList("b", "a")));
    }
    @Test public void refusesOtherPackage() {
        assertFalse(UpdatePolicy.accepts("mn", 13, Arrays.asList("a"), "other", 14, Arrays.asList("a")));
    }
    @Test public void refusesRollbackAndReinstall() {
        for (long code : new long[]{12,13}) assertFalse(UpdatePolicy.accepts("mn",13,Arrays.asList("a"),"mn",code,Arrays.asList("a")));
    }
    @Test public void refusesDifferentOrMissingSignatures() {
        assertFalse(UpdatePolicy.accepts("mn",13,Arrays.asList("a"),"mn",14,Arrays.asList("b")));
        assertFalse(UpdatePolicy.accepts("mn",13,Arrays.asList("a"),"mn",14,Collections.emptyList()));
        assertFalse(UpdatePolicy.accepts("mn",13,null,"mn",14,null));
        assertFalse(UpdatePolicy.accepts("mn",13,Arrays.asList(""),"mn",14,Arrays.asList("")));
        assertFalse(UpdatePolicy.accepts("mn",13,Arrays.asList("a"),"mn",14,Arrays.asList("a","b")));
    }
}
