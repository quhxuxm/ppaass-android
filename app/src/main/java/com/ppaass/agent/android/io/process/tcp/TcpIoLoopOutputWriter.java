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

class TcpIoLoopOutputWriter {
    public static final TcpIoLoopOutputWriter INSTANCE = new TcpIoLoopOutputWriter();

    private TcpIoLoopOutputWriter() {
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

    public void writeSynAckToQueue(TcpIoLoopInfo tcpIoLoopInfo) {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        ByteBuf mssByteBuf = Unpooled.buffer();
        mssByteBuf.writeShort(tcpIoLoopInfo.getMss());
        tcpPacketBuilder
                .addOption(new TcpHeaderOption(TcpHeaderOption.Kind.MSS, ByteBufUtil.getBytes(mssByteBuf)))
                .ack(true).syn(true);
        IpPacket ipPacket = this.buildIpPacket(tcpPacketBuilder, tcpIoLoopInfo);
        tcpIoLoopInfo.offerRemoteToDeviceIpPacket(ipPacket);
    }

    public void writeAckToQueue(TcpIoLoopInfo tcpIoLoopInfo, byte[] data) {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.ack(true).data(data);
        IpPacket ipPacket = this.buildIpPacket(tcpPacketBuilder, tcpIoLoopInfo);
        tcpIoLoopInfo.offerRemoteToDeviceIpPacket(ipPacket);
    }

    public void writePshAckToQueue(TcpIoLoopInfo tcpIoLoopInfo, byte[] data) {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder
                .ack(true).psh(true).data(data);
        IpPacket ipPacket = this.buildIpPacket(tcpPacketBuilder, tcpIoLoopInfo);
        tcpIoLoopInfo.offerRemoteToDeviceIpPacket(ipPacket);
    }

    public void writeRstAckToQueue(TcpIoLoopInfo tcpIoLoopInfo) {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.rst(true).ack(true);
        IpPacket ipPacket = this.buildIpPacket(tcpPacketBuilder, tcpIoLoopInfo);
        tcpIoLoopInfo.offerRemoteToDeviceIpPacket(ipPacket);
    }

    public void writeFinToQueue(TcpIoLoopInfo tcpIoLoopInfo) {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.fin(true);
        IpPacket ipPacket = this.buildIpPacket(tcpPacketBuilder, tcpIoLoopInfo);
        tcpIoLoopInfo.offerRemoteToDeviceIpPacket(ipPacket);
    }

    public void writeFinAckToQueue(TcpIoLoopInfo tcpIoLoopInfo) {
        TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder();
        tcpPacketBuilder.fin(true).ack(true);
        IpPacket ipPacket = this.buildIpPacket(tcpPacketBuilder, tcpIoLoopInfo);
        tcpIoLoopInfo.offerRemoteToDeviceIpPacket(ipPacket);
    }

    private IpPacket buildIpPacket(TcpPacketBuilder tcpPacketBuilder, TcpIoLoopInfo tcpIoLoopInfo) {
        short identification = (short) (Math.random() * 10000);
        IpV4Header ipV4Header =
                new IpV4HeaderBuilder()
                        .destinationAddress(tcpIoLoopInfo.getSourceAddress().getAddress())
                        .sourceAddress(tcpIoLoopInfo.getDestinationAddress().getAddress())
                        .protocol(IpDataProtocol.TCP).identification(identification).build();
        tcpPacketBuilder
                .sequenceNumber(tcpIoLoopInfo.getCurrentRemoteToDeviceSeq())
                .acknowledgementNumber(tcpIoLoopInfo.getCurrentRemoteToDeviceAck())
                .destinationPort(tcpIoLoopInfo.getSourcePort())
                .sourcePort(tcpIoLoopInfo.getDestinationPort()).window(65535);
        IpPacketBuilder ipPacketBuilder = new IpPacketBuilder();
        TcpPacket tcpPacket = tcpPacketBuilder.build();
        ipPacketBuilder.data(tcpPacket);
        ipPacketBuilder.header(ipV4Header);
        return ipPacketBuilder.build();
    }

    public void writeIpPacketToDevice(IpPacket ipPacket, TcpIoLoopInfo tcpIoLoopInfo,
                                      OutputStream remoteToDeviceStream) {
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
                Log.d(TcpIoLoopOutputWriter.class.getName(),
                        "WRITE TO DEVICE [" + packetType + ", NO DATA, size=" + tcpData.length + "], ip packet = " +
                                ipPacket +
                                ", tcp loop = " + tcpIoLoopInfo);
            } else {
                Log.d(TcpIoLoopOutputWriter.class.getName(),
                        "WRITE TO DEVICE [" + packetType + ", size=" + tcpData.length + "], ip packet = " + ipPacket +
                                ", tcp loop = " + tcpIoLoopInfo +
                                ", DATA:\n" +
                                ByteBufUtil.prettyHexDump(
                                        Unpooled.wrappedBuffer(tcpData)));
            }
            remoteToDeviceStream.write(IpPacketWriter.INSTANCE.write(ipPacket));
            remoteToDeviceStream.flush();
        } catch (IOException e) {
            Log.e(TcpIoLoopOutputWriter.class.getName(), "Fail to write ip packet to app because of exception.", e);
        }
    }
}
