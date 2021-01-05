package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import com.ppaass.agent.android.io.protocol.ip.*;
import com.ppaass.agent.android.io.protocol.tcp.TcpHeader;
import com.ppaass.agent.android.io.protocol.tcp.TcpHeaderOption;
import com.ppaass.agent.android.io.protocol.tcp.TcpPacket;
import com.ppaass.agent.android.io.protocol.tcp.TcpPacketBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

import java.io.IOException;
import java.io.OutputStream;

class TcpIoLoopRemoteToDeviceWriter {
    public static final TcpIoLoopRemoteToDeviceWriter INSTANCE = new TcpIoLoopRemoteToDeviceWriter();

    private TcpIoLoopRemoteToDeviceWriter() {
    }

    private String buildBasePacketType(TcpHeader tcpHeader) {
        if (tcpHeader.isSyn()) {
            return "SYN";
        }
        if (tcpHeader.isFin()) {
            return "FIN";
        }
        if (tcpHeader.isRst()) {
            return "RST";
        }
        if (tcpHeader.isPsh()) {
            return "PSH";
        }
        if (tcpHeader.isUrg()) {
            return "URG";
        }
        return null;
    }

    public IpPacket buildSynAck(byte[] sourceAddress, int sourcePort,
                                byte[] destinationAddress, int destinationPort, long sequenceNumber,
                                long acknowledgementNumber, int mss) {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        ByteBuf mssByteBuf = Unpooled.buffer();
        mssByteBuf.writeShort(mss);
        tcpPacketBuilder
                .addOption(new TcpHeaderOption(TcpHeaderOption.Kind.MSS, ByteBufUtil.getBytes(mssByteBuf)))
                .ack(true).syn(true);
        return this.buildIpPacket(tcpPacketBuilder, sourceAddress, sourcePort, destinationAddress, destinationPort,
                sequenceNumber, acknowledgementNumber);
    }

    public IpPacket buildAck(byte[] sourceAddress, int sourcePort,
                             byte[] destinationAddress, int destinationPort, long sequenceNumber,
                             long acknowledgementNumber, byte[] data) {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.ack(true).data(data);
        return this.buildIpPacket(tcpPacketBuilder, sourceAddress, sourcePort, destinationAddress, destinationPort,
                sequenceNumber, acknowledgementNumber);
    }

    public IpPacket buildPshAck(byte[] sourceAddress, int sourcePort,
                                byte[] destinationAddress, int destinationPort, long sequenceNumber,
                                long acknowledgementNumber, byte[] data) {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder
                .ack(true).psh(true).data(data);
        return this.buildIpPacket(tcpPacketBuilder, sourceAddress, sourcePort, destinationAddress, destinationPort,
                sequenceNumber, acknowledgementNumber);
    }

    public IpPacket buildRst(byte[] sourceAddress, int sourcePort,
                             byte[] destinationAddress, int destinationPort, long sequenceNumber,
                             long acknowledgementNumber) {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.rst(true);
        return this.buildIpPacket(tcpPacketBuilder, sourceAddress, sourcePort, destinationAddress, destinationPort,
                sequenceNumber, acknowledgementNumber);
    }

    public IpPacket buildFin(byte[] sourceAddress, int sourcePort,
                             byte[] destinationAddress, int destinationPort, long sequenceNumber,
                             long acknowledgementNumber) {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.fin(true);
        return this.buildIpPacket(tcpPacketBuilder, sourceAddress, sourcePort, destinationAddress, destinationPort,
                sequenceNumber, acknowledgementNumber);
    }

    public IpPacket buildFinAck(byte[] sourceAddress, int sourcePort,
                                byte[] destinationAddress, int destinationPort, long sequenceNumber,
                                long acknowledgementNumber) {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.fin(true).ack(true);
        return this.buildIpPacket(tcpPacketBuilder, sourceAddress, sourcePort, destinationAddress, destinationPort,
                sequenceNumber, acknowledgementNumber);
    }

    private IpPacket buildIpPacket(TcpPacketBuilder tcpPacketBuilder, byte[] sourceAddress, int sourcePort,
                                   byte[] destinationAddress, int destinationPort, long sequenceNumber,
                                   long acknowledgementNumber) {
        short identification = (short) (Math.random() * 10000);
        IpV4Header ipV4Header =
                new IpV4HeaderBuilder()
                        .destinationAddress(destinationAddress)
                        .sourceAddress(sourceAddress)
                        .protocol(IpDataProtocol.TCP).identification(identification).build();
        tcpPacketBuilder
                .sequenceNumber(sequenceNumber)
                .acknowledgementNumber(acknowledgementNumber)
                .destinationPort(destinationPort)
                .sourcePort(sourcePort).window(65535);
        ByteBuf timeStampByteBuf = Unpooled.buffer();
        timeStampByteBuf.writeInt(Math.abs((int) System.currentTimeMillis()));
        tcpPacketBuilder
                .addOption(new TcpHeaderOption(TcpHeaderOption.Kind.TSPOT, ByteBufUtil.getBytes(timeStampByteBuf)));
        timeStampByteBuf.clear();
        IpPacketBuilder ipPacketBuilder = new IpPacketBuilder();
        TcpPacket tcpPacket = tcpPacketBuilder.build();
        ipPacketBuilder.data(tcpPacket);
        ipPacketBuilder.header(ipV4Header);
        return ipPacketBuilder.build();
    }

    public void writeIpPacketToDevice(IpPacket ipPacket, String loopKey, OutputStream remoteToDeviceStream) {
        try {
            TcpPacket tcpPacket = (TcpPacket) ipPacket.getData();
            TcpHeader tcpHeader = tcpPacket.getHeader();
            String packetType = this.buildBasePacketType(tcpHeader);
            if (tcpHeader.isAck()) {
                if (packetType != null) {
                    packetType += " ACK";
                } else {
                    packetType = "ACK";
                }
            }
            byte[] tcpData = tcpPacket.getData();
            if (tcpData.length == 0) {
                Log.d(TcpIoLoopRemoteToDeviceWriter.class.getName(),
                        "WRITE TO DEVICE [" + packetType + ", NO DATA, size=" + tcpData.length + "], ip packet = " +
                                ipPacket +
                                ", tcp loop key= '" + loopKey + "'");
            } else {
                Log.d(TcpIoLoopRemoteToDeviceWriter.class.getName(),
                        "WRITE TO DEVICE [" + packetType + ", size=" + tcpData.length + "], ip packet = " + ipPacket +
                                ", tcp loop key= '" + loopKey +
                                "', DATA:\n" +
                                ByteBufUtil.prettyHexDump(
                                        Unpooled.wrappedBuffer(tcpData)));
            }
            remoteToDeviceStream.write(IpPacketWriter.INSTANCE.write(ipPacket));
            remoteToDeviceStream.flush();
        } catch (IOException e) {
            Log.e(TcpIoLoopRemoteToDeviceWriter.class.getName(), "Fail to write ip packet to app because of exception.",
                    e);
        }
    }
}
