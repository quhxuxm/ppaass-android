package com.ppaass.agent.android.io.protocol;

import com.ppaass.agent.android.io.protocol.ip.IpDataProtocol;
import com.ppaass.agent.android.io.protocol.ip.IpV4Header;
import com.ppaass.agent.android.io.protocol.ip.IpV6Header;
import com.ppaass.agent.android.io.protocol.tcp.TcpPacket;

import java.nio.ByteBuffer;

public class TcpPacketWriter {
    private static final int FAKE_HEADER_LENGTH = 12;
    public static final TcpPacketWriter INSTANCE = new TcpPacketWriter();

    private TcpPacketWriter() {
    }

    private int convertBoolean(boolean value) {
        if (value) {
            return 1;
        }
        return 0;
    }

    public byte[] write(TcpPacket packet, IpV4Header ipHeader) {
        ByteBuffer fakeHeaderByteBuffer = ByteBuffer.allocate(12);
        fakeHeaderByteBuffer.put(ipHeader.getSourceAddress());
        fakeHeaderByteBuffer.put(ipHeader.getDestinationAddress());
        fakeHeaderByteBuffer.put((byte) 0);
        fakeHeaderByteBuffer.put((byte) IpDataProtocol.TCP.getValue());
        fakeHeaderByteBuffer.putShort((short) (packet.getHeader().getOffset() * 4 + packet.getData().length));
        fakeHeaderByteBuffer.flip();
        ByteBuffer byteBufferForChecksum =
                ByteBuffer.allocate(packet.getHeader().getOffset() * 4 + 12 + packet.getData().length);
        byteBufferForChecksum.put(fakeHeaderByteBuffer);
        byte[] tcpPacketBytesForChecksum = this.writeWithGivenChecksum(packet, 0);
        byteBufferForChecksum.put(tcpPacketBytesForChecksum);
        byteBufferForChecksum.flip();
        int checksum = ChecksumUtil.INSTANCE.checksum(byteBufferForChecksum.array());
        return this.writeWithGivenChecksum(packet, checksum);
    }

    public byte[] write(TcpPacket packet, IpV6Header ipHeader) {
        throw new UnsupportedOperationException("Do not support IPv6");
    }

    private byte[] writeWithGivenChecksum(TcpPacket packet, int checksum) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(packet.getHeader().getOffset() * 4 + packet.getData().length);
        byteBuffer.putShort((short) packet.getHeader().getSourcePort());
        byteBuffer.putShort((short) packet.getHeader().getDestinationPort());
        byteBuffer.putInt((int) packet.getHeader().getSequenceNumber());
        byteBuffer.putInt((int) packet.getHeader().getAcknowledgementNumber());
        int offsetAndResolvedAndUAPRSF =(packet.getHeader().getOffset() << 6) | (packet.getHeader().getResolve());
        offsetAndResolvedAndUAPRSF = offsetAndResolvedAndUAPRSF << 6;
        int flags = (this.convertBoolean(packet.getHeader().isUrg()) << 5) |
                (this.convertBoolean(packet.getHeader().isAck()) << 4) |
                (this.convertBoolean(packet.getHeader().isPsh()) << 3) |
                (this.convertBoolean(packet.getHeader().isRst()) << 2) |
                (this.convertBoolean(packet.getHeader().isSyn()) << 1) |
                this.convertBoolean(packet.getHeader().isFin());
        offsetAndResolvedAndUAPRSF = offsetAndResolvedAndUAPRSF | flags;
        byteBuffer.putShort((short) offsetAndResolvedAndUAPRSF);
        byteBuffer.putShort((short) packet.getHeader().getWindow());
        byteBuffer.putShort((short) checksum);
        byteBuffer.putShort((short) packet.getHeader().getUrgPointer());
        byteBuffer.put(packet.getHeader().getOptionAndPadding());
        byteBuffer.put(packet.getData());
        byteBuffer.flip();
        return byteBuffer.array();
    }
}
