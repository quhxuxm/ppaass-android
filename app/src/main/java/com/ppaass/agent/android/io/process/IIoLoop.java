package com.ppaass.agent.android.io.process;

import com.ppaass.agent.android.io.protocol.ip.IpPacket;

import java.net.InetAddress;

public interface IIoLoop extends Runnable {
    String getKey();

    void execute(IpPacket inputIpPacket);

    void destroy();

    InetAddress getSourceAddress();

    InetAddress getDestinationAddress();

    int getSourcePort();

    int getDestinationPort();
}
