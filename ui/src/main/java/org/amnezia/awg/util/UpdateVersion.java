package org.amnezia.awg.util;

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SemVer precedence, including numeric prerelease identifiers (beta.10 > beta.2). */
public final class UpdateVersion {
    private static final Pattern VERSION = Pattern.compile("^v?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$");
    private UpdateVersion() { }
    private static Matcher parse(String value) {
        Matcher m = VERSION.matcher(value);
        if (!m.matches()) throw new IllegalArgumentException("Invalid version");
        if (m.group(4) != null) for (String id : m.group(4).split("\\."))
            if (id.matches("[0-9]+") && id.length() > 1 && id.startsWith("0")) throw new IllegalArgumentException("Leading zero");
        return m;
    }
    public static int compare(String left, String right) {
        Matcher a = parse(left), b = parse(right);
        for (int i = 1; i <= 3; i++) {
            int cmp = new BigInteger(a.group(i)).compareTo(new BigInteger(b.group(i)));
            if (cmp != 0) return cmp;
        }
        String x = a.group(4), y = b.group(4);
        if (x == null || y == null) return x == null ? (y == null ? 0 : 1) : -1;
        String[] ax = x.split("\\."), by = y.split("\\.");
        for (int i = 0; i < Math.min(ax.length, by.length); i++) {
            boolean an = ax[i].matches("[0-9]+"), bn = by[i].matches("[0-9]+");
            int cmp = an && bn ? new BigInteger(ax[i]).compareTo(new BigInteger(by[i])) : an != bn ? (an ? -1 : 1) : ax[i].compareTo(by[i]);
            if (cmp != 0) return cmp;
        }
        return Integer.compare(ax.length, by.length);
    }
    public static boolean isNewer(String candidate, String installed) {
        try { return compare(candidate, installed) > 0; } catch (IllegalArgumentException e) { return false; }
    }
    public static boolean isStable(String version) {
        try { return !version.startsWith("v") && parse(version).group(4) == null; } catch (IllegalArgumentException e) { return false; }
    }
    public static boolean isBeta(String version) {
        try { return !version.startsWith("v") && parse(version).group(4) != null && parse(version).group(4).matches("beta\\.[1-9][0-9]*"); }
        catch (IllegalArgumentException e) { return false; }
    }
}
