package com.ppaass.agent.android.io.process.tcp;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

public class TcpIoLoopPacketAckLockHolder {
    public static TcpIoLoopPacketAckLockHolder INSTANCE = new TcpIoLoopPacketAckLockHolder();
    private final Map<String, Semaphore> packetLocks;

    private TcpIoLoopPacketAckLockHolder() {
        this.packetLocks = new HashMap<>();
    }

    public Map<String, Semaphore> getPacketAckLocks() {
        return packetLocks;
    }
}
