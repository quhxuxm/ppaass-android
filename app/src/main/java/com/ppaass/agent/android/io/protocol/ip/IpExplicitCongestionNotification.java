package com.ppaass.agent.android.io.protocol.ip;

public class IpExplicitCongestionNotification {
    private boolean lowCost;
    private int resolve;

    public boolean isLowCost() {
        return lowCost;
    }

    public void setLowCost(boolean lowCost) {
        this.lowCost = lowCost;
    }

    public int getResolve() {
        return resolve;
    }

    public void setResolve(int resolve) {
        this.resolve = resolve;
    }

    @Override
    public String toString() {
        return "IpExplicitCongestionNotification{" +
                "lowCost=" + lowCost +
                ", resolve=" + resolve +
                '}';
    }
}
