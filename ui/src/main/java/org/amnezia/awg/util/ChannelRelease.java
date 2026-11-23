package org.amnezia.awg.util;

/** Validated metadata, shared by channel selection, download and installation. */
public final class ChannelRelease {
    public static final String RELEASES = "https://github.com/artemvarlapov2005/amneziaWG-MN/releases";
    public final String channel, tag, versionName, apkUrl, sha256, notes;
    public final long versionCode;
    public ChannelRelease(String channel, String tag, String versionName, long versionCode, String apkUrl, String sha256, String notes) {
        if (versionName.length() > 96 || tag.length() > 97) throw new IllegalArgumentException("Version too long");
        if (!(channel.equals("stable") && UpdateVersion.isStable(versionName)) && !(channel.equals("beta") && UpdateVersion.isBeta(versionName)))
            throw new IllegalArgumentException("Channel/version mismatch");
        if ((!tag.equals(versionName) && !tag.equals("v" + versionName)) || versionCode <= 0 || versionCode > 2100000000L || !sha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid release metadata");
        if (!apkUrl.equals(RELEASES + "/download/" + tag + "/AmneziaWG-MN-" + versionName + "-arm64.apk"))
            throw new IllegalArgumentException("Untrusted APK URL");
        this.channel = channel; this.tag = tag; this.versionName = versionName; this.versionCode = versionCode;
        this.apkUrl = apkUrl; this.sha256 = sha256; this.notes = notes;
    }
    public boolean allowed(String selectedChannel) { return channel.equals("stable") || selectedChannel.equals("beta"); }
    public boolean upgrades(String installedVersion, long installedCode) { return versionCode > installedCode && UpdateVersion.isNewer(versionName, installedVersion); }
    public static ChannelRelease select(String selectedChannel, String installedVersion, long installedCode, ChannelRelease stable, ChannelRelease beta) {
        if (!selectedChannel.equals("stable") && !selectedChannel.equals("beta")) throw new IllegalArgumentException("Invalid channel");
        ChannelRelease chosen = null;
        for (ChannelRelease r : new ChannelRelease[]{stable, beta}) {
            if (r == null || !r.allowed(selectedChannel) || !r.upgrades(installedVersion, installedCode)) continue;
            if (chosen == null || UpdateVersion.compare(r.versionName, chosen.versionName) > 0 ||
                    (UpdateVersion.compare(r.versionName, chosen.versionName) == 0 && r.versionCode > chosen.versionCode)) chosen = r;
        }
        return chosen;
    }
}
