package com.ppaass.agent.android.io.protocol.ip;

public class IpDifferentiatedServices {
    private int importance;
    private boolean delay;
    private boolean highStream;
    private boolean highAvailability;

    public int getImportance() {
        return importance;
    }

    public void setImportance(int importance) {
        this.importance = importance;
    }

    public boolean isDelay() {
        return delay;
    }

    public void setDelay(boolean delay) {
        this.delay = delay;
    }

    public boolean isHighStream() {
        return highStream;
    }

    public void setHighStream(boolean highStream) {
        this.highStream = highStream;
    }

    public boolean isHighAvailability() {
        return highAvailability;
    }

    public void setHighAvailability(boolean highAvailability) {
        this.highAvailability = highAvailability;
    }

    @Override
    public String toString() {
        return "IpDifferentiatedServices{" +
                "importance=" + importance +
                ", delay=" + delay +
                ", highStream=" + highStream +
                ", highAvailability=" + highAvailability +
                '}';
    }
}
