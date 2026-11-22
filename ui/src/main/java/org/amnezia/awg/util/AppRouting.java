package org.amnezia.awg.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.amnezia.awg.config.BadConfigException;
import org.amnezia.awg.config.Config;
import org.amnezia.awg.config.Interface;

/** Changes only app routing; keeps all tunnel and peer parameters. */
public final class AppRouting {
    private AppRouting() { }
    public static Config apply(Config config, int mode, Collection<String> packages) throws BadConfigException {
        if (mode < 0 || mode > 2 || (mode != 0 && packages.isEmpty())) throw new IllegalArgumentException("Choose applications");
        List<String> lines = new ArrayList<>();
        for (String line : config.getInterface().toAwgQuickString().split("\\n")) {
            if (!line.startsWith("IncludedApplications =") && !line.startsWith("ExcludedApplications =")) lines.add(line);
        }
        if (mode != 0) {
            for (String pkg : packages) if (!pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")) throw new IllegalArgumentException("Invalid package");
            lines.add((mode == 1 ? "IncludedApplications = " : "ExcludedApplications = ") + String.join(", ", packages));
        }
        return new Config.Builder().setInterface(Interface.parse(lines)).addPeers(config.getPeers()).build();
    }
}
