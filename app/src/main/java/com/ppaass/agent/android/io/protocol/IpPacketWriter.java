package com.ppaass.agent.android.io.protocol;

import com.ppaass.agent.android.io.protocol.ip.IpPacket;

public class IpPacketWriter {
    public static final IpPacketWriter INSTANCE = new IpPacketWriter();

    private IpPacketWriter() {
    }

    public byte[] write(IpPacket packet) {
        return null;
    }
}
