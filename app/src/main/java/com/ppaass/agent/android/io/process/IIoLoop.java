package com.ppaass.agent.android.io.process;

import com.ppaass.agent.android.io.protocol.ip.IpPacket;

import java.net.InetAddress;

public interface IIoLoop<OutputData> {
    String getKey();

    void init();

    void start();

    void stop();

    void offerInputIpPacket(IpPacket ipPacket);

    void offerOutputData(OutputData outputData);

    InetAddress getSourceAddress();

    InetAddress getDestinationAddress();

    int getSourcePort();

    int getDestinationPort();
}
