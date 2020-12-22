package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import com.ppaass.agent.android.io.protocol.ip.*;
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

    public void writeIpPacket(IpPacket ipPacket, TcpIoLoop tcpIoLoop, OutputStream outputStream) {
        try {
            TcpPacket tcpPacket = (TcpPacket) ipPacket.getData();
            byte[] tcpData = tcpPacket.getData();
            Log.d(TcpIoLoopOutputWriter.class.getName(),
                    "WRITE TO APP, ip packet = " + ipPacket + ", tcp loop = " + tcpIoLoop + ", DATA:\n" +
                            ByteBufUtil.prettyHexDump(
                                    Unpooled.wrappedBuffer(tcpData)));
            outputStream.write(IpPacketWriter.INSTANCE.write(ipPacket));
            outputStream.flush();
        } catch (IOException e) {
            Log.e(TcpIoLoopOutputWriter.class.getName(), "Fail to write ip packet to app because of exception.", e);
        }
    }

    public void writeSynAckForTcpIoLoop(TcpIoLoop tcpIoLoop) {
        TcpPacketBuilder synAckTcpPacketBuilder = new TcpPacketBuilder();
        ByteBuf mssByteBuf=Unpooled.buffer();
        mssByteBuf.writeShort(tcpIoLoop.getMss());
        synAckTcpPacketBuilder
                .addOption(new TcpHeaderOption(TcpHeaderOption.Kind.MSS, ByteBufUtil.getBytes(mssByteBuf)))
                .ack(true).syn(true);
        this.writeIpPacket(this.buildIpPacket(synAckTcpPacketBuilder, tcpIoLoop), tcpIoLoop, tcpIoLoop.getVpnOutput());
    }

    public void writeAckForTcpIoLoop(TcpIoLoop tcpIoLoop, byte[] data) {
        TcpPacketBuilder synAckTcpPacketBuilder = new TcpPacketBuilder();
        synAckTcpPacketBuilder.ack(true).data(data);
        this.writeIpPacket(this.buildIpPacket(synAckTcpPacketBuilder, tcpIoLoop), tcpIoLoop, tcpIoLoop.getVpnOutput());
    }

    public void writePshAckForTcpIoLoop(TcpIoLoop tcpIoLoop, byte[] data) {
        TcpPacketBuilder synAckTcpPacketBuilder = new TcpPacketBuilder();
        synAckTcpPacketBuilder
                .ack(true).psh(true).data(data);
        this.writeIpPacket(this.buildIpPacket(synAckTcpPacketBuilder, tcpIoLoop), tcpIoLoop, tcpIoLoop.getVpnOutput());
    }

    public void writeRstForTcpIoLoop(TcpIoLoop tcpIoLoop) {
        TcpPacketBuilder synAckTcpPacketBuilder = new TcpPacketBuilder();
        synAckTcpPacketBuilder.rst(true);
        this.writeIpPacket(this.buildIpPacket(synAckTcpPacketBuilder, tcpIoLoop), tcpIoLoop, tcpIoLoop.getVpnOutput());
    }

    public void writeFinForTcpIoLoop(TcpIoLoop tcpIoLoop) {
        TcpPacketBuilder synAckTcpPacketBuilder = new TcpPacketBuilder();
        synAckTcpPacketBuilder.fin(true);
        this.writeIpPacket(this.buildIpPacket(synAckTcpPacketBuilder, tcpIoLoop), tcpIoLoop, tcpIoLoop.getVpnOutput());
    }

    public void writeFinAckForTcpIoLoop(TcpIoLoop tcpIoLoop) {
        TcpPacketBuilder synAckTcpPacketBuilder = new TcpPacketBuilder();
        synAckTcpPacketBuilder.fin(true).ack(true);
        this.writeIpPacket(this.buildIpPacket(synAckTcpPacketBuilder, tcpIoLoop), tcpIoLoop, tcpIoLoop.getVpnOutput());
    }

    private IpPacket buildIpPacket(TcpPacketBuilder tcpPacketBuilder, TcpIoLoop tcpIoLoop) {
        short identification = (short) (Math.random() * 10000);
        IpV4Header ipV4Header =
                new IpV4HeaderBuilder()
                        .destinationAddress(tcpIoLoop.getSourceAddress().getAddress())
                        .sourceAddress(tcpIoLoop.getDestinationAddress().getAddress())
                        .protocol(IpDataProtocol.TCP).identification(identification).build();
        tcpPacketBuilder
                .sequenceNumber(tcpIoLoop.getVpnToAppSequenceNumber())
                .acknowledgementNumber(tcpIoLoop.getVpnToAppAcknowledgementNumber())
                .destinationPort(tcpIoLoop.getSourcePort())
                .sourcePort(tcpIoLoop.getDestinationPort()).window(65535);
        IpPacketBuilder ipPacketBuilder = new IpPacketBuilder();
        TcpPacket tcpPacket = tcpPacketBuilder.build();
        ipPacketBuilder.data(tcpPacket);
        ipPacketBuilder.header(ipV4Header);
        return ipPacketBuilder.build();
    }
}
