package com.ppaass.agent.android.io.protocol;

import com.ppaass.agent.android.io.protocol.tcp.TcpHeader;
import com.ppaass.agent.android.io.protocol.tcp.TcpPacket;

import java.nio.ByteBuffer;

public class TcpPacketReader {
    public static final TcpPacketReader INSTANCE = new TcpPacketReader();

    private TcpPacketReader() {
    }

    public TcpPacket parse(byte[] input) {
        ByteBuffer byteBuffer = ByteBuffer.wrap(input);
        TcpHeader header = new TcpHeader();
        header.setSourcePort(byteBuffer.getShort() & 0xFFFF);
        header.setDestinationPort(byteBuffer.getShort() & 0xFFFF);
        header.setSequenceNumber(byteBuffer.getInt() & 0xFFFFFFFFL);
        header.setAcknowledgementNumber(byteBuffer.getInt() & 0xFFFFFFFFL);
        int offsetAndResolvedAndUAPRSF = byteBuffer.getShort() & 0xFFFF;
        header.setOffset(offsetAndResolvedAndUAPRSF >> 12);
        header.setResolve((offsetAndResolvedAndUAPRSF >> 6) & 0x3F);
        header.setUrg(((offsetAndResolvedAndUAPRSF >> 5) & 1) != 0);
        header.setAck(((offsetAndResolvedAndUAPRSF >> 4) & 1) != 0);
        header.setPsh(((offsetAndResolvedAndUAPRSF >> 3) & 1) != 0);
        header.setRst(((offsetAndResolvedAndUAPRSF >> 2) & 1) != 0);
        header.setSyn(((offsetAndResolvedAndUAPRSF >> 1) & 1) != 0);
        header.setFin((offsetAndResolvedAndUAPRSF & 1) != 0);
        header.setWindow(byteBuffer.getShort() & 0xFFFF);
        header.setChecksum(byteBuffer.getShort() & 0xFFFF);
        header.setUrgPointer(byteBuffer.getShort() & 0xFFFF);
        int headerLength = header.getOffset() * 4;
        byte[] optionAndPadding = new byte[headerLength - IProtocolConst.MIN_TCP_HEADER_LENGTH];
        byteBuffer.get(optionAndPadding);
        header.setOptionAndPadding(optionAndPadding);
        int dataLength = input.length - headerLength;
        byte[] data = new byte[dataLength];
        byteBuffer.get(data);
        return new TcpPacket(header, data);
    }
}
