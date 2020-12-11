package com.ppaass.agent.android.io.protocol;

import com.ppaass.agent.android.io.protocol.udp.UdpHeader;
import com.ppaass.agent.android.io.protocol.udp.UdpPacket;

import java.nio.ByteBuffer;

public class UdpPacketReader {
    public static final UdpPacketReader INSTANCE = new UdpPacketReader();

    private UdpPacketReader() {
    }

    public UdpPacket parse(byte[] input) {
        ByteBuffer byteBuffer = ByteBuffer.wrap(input);
        UdpHeader header = new UdpHeader();
        header.setSourcePort(byteBuffer.getShort() & 0xFFFF);
        header.setDestinationPort(byteBuffer.getShort() & 0xFFFF);
        header.setTotalLength(byteBuffer.getShort() & 0xFFFF);
        header.setChecksum(byteBuffer.getShort() & 0xFFFF);
        byte[] data = new byte[input.length - IProtocolConst.MIN_UDP_HEADER_LENGTH];
        byteBuffer.get(data);
        return new UdpPacket(header, data);
    }
}
