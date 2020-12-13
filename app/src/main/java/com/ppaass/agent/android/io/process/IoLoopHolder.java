package com.ppaass.agent.android.io.process;

import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class IoLoopHolder {
    public static final IoLoopHolder INSTANCE = new IoLoopHolder();
    private final ConcurrentMap<String, IIoLoop<?>> ioLoops;

    private IoLoopHolder() {
        this.ioLoops = new ConcurrentHashMap<>();
    }

    public String generateLoopKey(InetAddress sourceAddress, int sourcePort, InetAddress destinationAddress,
                                  int destinationPort) {
        return String.format("%s:%s->%s:%s", sourceAddress.getHostAddress(), sourcePort,
                destinationAddress.getHostAddress(), destinationPort);
    }

    public ConcurrentMap<String, IIoLoop<?>> getIoLoops() {
        return ioLoops;
    }
}
