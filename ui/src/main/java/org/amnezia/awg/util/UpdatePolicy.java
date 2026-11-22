package org.amnezia.awg.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The update must preserve application identity and move strictly forwards. */
public final class UpdatePolicy {
    private UpdatePolicy() { }
    public static boolean accepts(String installedPackage, long installedCode, List<String> installedSigners,
                                  String candidatePackage, long candidateCode, List<String> candidateSigners) {
        if (installedPackage == null || !installedPackage.equals(candidatePackage) ||
                candidateCode <= installedCode || installedSigners == null || installedSigners.isEmpty() ||
                candidateSigners == null || candidateSigners.isEmpty()) return false;
        if (installedSigners.contains(null) || candidateSigners.contains(null) ||
                installedSigners.contains("") || candidateSigners.contains("")) return false;
        List<String> expected = new ArrayList<>(installedSigners);
        List<String> actual = new ArrayList<>(candidateSigners);
        Collections.sort(expected); Collections.sort(actual);
        return expected.equals(actual);
    }
}
