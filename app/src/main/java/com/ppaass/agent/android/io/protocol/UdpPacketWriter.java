package com.ppaass.agent.android.io.protocol;

import com.ppaass.agent.android.io.protocol.ip.IpV4Header;
import com.ppaass.agent.android.io.protocol.ip.IpV6Header;
import com.ppaass.agent.android.io.protocol.udp.UdpPacket;

public class UdpPacketWriter {
    public static final UdpPacketWriter INSTANCE = new UdpPacketWriter();

    private UdpPacketWriter() {
    }

    public byte[] write(UdpPacket packet, IpV4Header ipHeader) {
        return null;
    }
    public byte[] write(UdpPacket packet, IpV6Header ipHeader) {
        throw new UnsupportedOperationException("Do not support IPv6");
    }
}
