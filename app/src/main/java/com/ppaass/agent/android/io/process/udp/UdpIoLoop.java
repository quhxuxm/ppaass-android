package com.ppaass.agent.android.io.process.udp;

import com.ppaass.agent.android.io.process.IIoLoop;
import com.ppaass.agent.android.io.protocol.ip.IpPacket;
import io.netty.bootstrap.Bootstrap;

import java.net.InetAddress;

public class UdpIoLoop implements IIoLoop<UdpIoLoopOutputData> {
    private final InetAddress sourceAddress;
    private final InetAddress destinationAddress;
    private final int sourcePort;
    private final int destinationPort;
    private final String key;
    private final Bootstrap proxyUdpBootstrap;

    public UdpIoLoop(InetAddress sourceAddress, InetAddress destinationAddress, int sourcePort, int destinationPort,
                     String key, Bootstrap proxyUdpBootstrap) {
        this.sourceAddress = sourceAddress;
        this.destinationAddress = destinationAddress;
        this.sourcePort = sourcePort;
        this.destinationPort = destinationPort;
        this.key = key;
        this.proxyUdpBootstrap = proxyUdpBootstrap;
    }

    @Override
    public String getKey() {
        return null;
    }

    @Override
    public void init() {
    }

    @Override
    public void start() {
    }

    @Override
    public void offerInputIpPacket(IpPacket ipPacket) {
    }

    @Override
    public void offerOutputData(UdpIoLoopOutputData udpIoLoopOutputData) {
    }

    @Override
    public void stop() {
    }

    @Override
    public InetAddress getSourceAddress() {
        return null;
    }

    @Override
    public InetAddress getDestinationAddress() {
        return null;
    }

    @Override
    public int getSourcePort() {
        return 0;
    }

    @Override
    public int getDestinationPort() {
        return 0;
    }
}
