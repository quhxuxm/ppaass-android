package com.ppaass.agent.android.io.protocol.ip;

public class IpFlags {
    private final int resolved;
    private boolean df;
    private boolean mf;

    public IpFlags() {
        this.resolved = 0;
    }

    public int getResolved() {
        return resolved;
    }

    public void setDf(boolean df) {
        this.df = df;
    }

    public void setMf(boolean mf) {
        this.mf = mf;
    }

    public boolean isDf() {
        return df;
    }

    public boolean isMf() {
        return mf;
    }

    @Override
    public String toString() {
        return "IpFlags{" +
                "resolved=" + resolved +
                ", df=" + df +
                ", mf=" + mf +
                '}';
    }
}
