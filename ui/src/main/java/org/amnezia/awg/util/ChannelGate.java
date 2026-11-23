package org.amnezia.awg.util;

/** Generation check and commit share one monitor, preventing a late old-channel download. */
public final class ChannelGate {
    public static final class Snapshot {
        public final String channel; public final long generation;
        private Snapshot(String channel, long generation) { this.channel = channel; this.generation = generation; }
    }
    private String channel; private long generation;
    public ChannelGate(String channel, long generation) { this.channel = channel; this.generation = generation; }
    public synchronized Snapshot snapshot() { return new Snapshot(channel, generation); }
    public synchronized boolean current(Snapshot s) { return s.generation == generation && s.channel.equals(channel); }
    public synchronized Snapshot change(String value) {
        if (!value.equals("stable") && !value.equals("beta")) throw new IllegalArgumentException("Invalid channel");
        channel = value; generation++; return snapshot();
    }
    public synchronized boolean commit(Snapshot s, Runnable action) { if (!current(s)) return false; action.run(); return true; }
}
