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

public class TcpIoLoopOutputWriter {
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

    public void writeIpPacket(IpPacket ipPacket, TcpIoLoopInfo tcpIoLoopInfo, OutputStream remoteToDeviceStream) {
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
                        "WRITE TO DEVICE [" + packetType + ", NO DATA, size=" + tcpData.length + "], ip packet = " + ipPacket +
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

    public void writeSynAck(TcpIoLoopInfo tcpIoLoopInfo, OutputStream remoteToDeviceStream) {
        TcpPacketBuilder synAckTcpPacketBuilder = new TcpPacketBuilder();
        ByteBuf mssByteBuf = Unpooled.buffer();
        mssByteBuf.writeShort(tcpIoLoopInfo.getMss());
        synAckTcpPacketBuilder
                .addOption(new TcpHeaderOption(TcpHeaderOption.Kind.MSS, ByteBufUtil.getBytes(mssByteBuf)))
                .ack(true).syn(true);
        this.writeIpPacket(this.buildIpPacket(synAckTcpPacketBuilder, tcpIoLoopInfo), tcpIoLoopInfo, remoteToDeviceStream);
    }

    public void writeAck(TcpIoLoopInfo tcpIoLoopInfo, byte[] data, OutputStream remoteToDeviceStream) {
        TcpPacketBuilder synAckTcpPacketBuilder = new TcpPacketBuilder();
        synAckTcpPacketBuilder.ack(true).data(data);
        this.writeIpPacket(this.buildIpPacket(synAckTcpPacketBuilder, tcpIoLoopInfo), tcpIoLoopInfo, remoteToDeviceStream);
    }

    public void writePshAck(TcpIoLoopInfo tcpIoLoopInfo, byte[] data, OutputStream remoteToDeviceStream) {
        TcpPacketBuilder synAckTcpPacketBuilder = new TcpPacketBuilder();
        synAckTcpPacketBuilder
                .ack(true).psh(true).data(data);
        this.writeIpPacket(this.buildIpPacket(synAckTcpPacketBuilder, tcpIoLoopInfo), tcpIoLoopInfo, remoteToDeviceStream);
    }

    public void writeRstAck(TcpIoLoopInfo tcpIoLoopInfo, OutputStream remoteToDeviceStream) {
        TcpPacketBuilder synAckTcpPacketBuilder = new TcpPacketBuilder();
        synAckTcpPacketBuilder.rst(true).ack(true);
        this.writeIpPacket(this.buildIpPacket(synAckTcpPacketBuilder, tcpIoLoopInfo), tcpIoLoopInfo, remoteToDeviceStream);
    }

    public void writeFin(TcpIoLoopInfo tcpIoLoopInfo, OutputStream remoteToDeviceStream) {
        TcpPacketBuilder synAckTcpPacketBuilder = new TcpPacketBuilder();
        synAckTcpPacketBuilder.fin(true);
        this.writeIpPacket(this.buildIpPacket(synAckTcpPacketBuilder, tcpIoLoopInfo), tcpIoLoopInfo, remoteToDeviceStream);
    }

    public void writeFinAck(TcpIoLoopInfo tcpIoLoopInfo, OutputStream remoteToDeviceStream) {
        TcpPacketBuilder synAckTcpPacketBuilder = new TcpPacketBuilder();
        synAckTcpPacketBuilder.fin(true).ack(true);
        this.writeIpPacket(this.buildIpPacket(synAckTcpPacketBuilder, tcpIoLoopInfo), tcpIoLoopInfo, remoteToDeviceStream);
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
}
