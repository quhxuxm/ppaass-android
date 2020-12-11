package com.ppaass.agent.android.io.process.udp;

import com.ppaass.agent.android.io.process.IIoLoop;
import com.ppaass.agent.android.io.protocol.ip.IpPacket;

import java.net.InetAddress;

public class UdpIoLoop implements IIoLoop {
    private final InetAddress sourceAddress;
    private final InetAddress destinationAddress;
    private final int sourcePort;
    private final int destinationPort;
    private final String key;

    public UdpIoLoop(InetAddress sourceAddress, InetAddress destinationAddress, int sourcePort, int destinationPort,
                     String key) {
        this.sourceAddress = sourceAddress;
        this.destinationAddress = destinationAddress;
        this.sourcePort = sourcePort;
        this.destinationPort = destinationPort;
        this.key = key;
    }

    @Override
    public String getKey() {
        return null;
    }

    @Override
    public void loop() {
    }

    @Override
    public void offerIpPacket(IpPacket ipPacket) {
    }

    @Override
    public void stop() {
    }
}
