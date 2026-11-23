package org.amnezia.awg.util;
import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
public final class UpdateVersion {
    private static final Pattern VERSION = Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([A-Za-z0-9.-]+))?$");
    private UpdateVersion() { }
    public static boolean isNewer(String candidate, String installed) {
        Matcher a = VERSION.matcher(candidate), b = VERSION.matcher(installed);
        if (!a.matches() || !b.matches()) return false;
        for (int i=1; i<=3; i++) {
            int cmp = new BigInteger(a.group(i)).compareTo(new BigInteger(b.group(i)));
            if (cmp != 0) return cmp > 0;
        }
        // Only promote a prerelease to the stable version; never offer a downgrade.
        return a.group(4) == null && b.group(4) != null;
    }
}
