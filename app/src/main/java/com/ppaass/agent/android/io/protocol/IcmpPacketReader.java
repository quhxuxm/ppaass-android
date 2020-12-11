package com.ppaass.agent.android.io.protocol;

import com.ppaass.agent.android.io.protocol.icmp.IcmpPacket;

public class IcmpPacketReader {
    public static final IcmpPacketReader INSTANCE = new IcmpPacketReader();

    private IcmpPacketReader() {
    }

    public IcmpPacket parse(byte[] input) {
        return null;
    }
}
