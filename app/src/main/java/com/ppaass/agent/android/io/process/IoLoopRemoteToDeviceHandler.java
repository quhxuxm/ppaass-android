package com.ppaass.agent.android.io.process;

import android.util.Log;
import com.ppaass.common.log.PpaassLogger;
import com.ppaass.protocol.base.ip.IpDataProtocol;
import com.ppaass.protocol.base.ip.IpPacket;
import com.ppaass.protocol.common.util.UUIDUtil;
import com.ppaass.protocol.vpn.message.ProxyMessage;
import com.ppaass.protocol.vpn.message.ProxyMessageBodyType;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.io.OutputStream;
import java.net.InetAddress;
import java.util.concurrent.ConcurrentMap;

@ChannelHandler.Sharable
public class IoLoopRemoteToDeviceHandler extends SimpleChannelInboundHandler<ProxyMessage> {
    private final OutputStream remoteToDeviceStream;
    private final ConcurrentMap<String, TcpIoLoop> tcpIoLoops;

    public IoLoopRemoteToDeviceHandler(OutputStream remoteToDeviceStream,
                                       ConcurrentMap<String, TcpIoLoop> tcpIoLoops) {
        this.remoteToDeviceStream = remoteToDeviceStream;
        this.tcpIoLoops = tcpIoLoops;
    }

    @Override
    public void channelRead0(ChannelHandlerContext proxyChannelContext, ProxyMessage proxyMessage)
            throws Exception {
        ProxyMessageBodyType proxyMessageBodyType = proxyMessage.getBody().getBodyType();
        if (ProxyMessageBodyType.TCP_CONNECT_SUCCESS == proxyMessageBodyType) {
            String ioLoopKey = IoLoopUtil.INSTANCE
                    .generateIoLoopKey(IpDataProtocol.TCP, proxyMessage.getBody().getSourceHost(),
                            proxyMessage.getBody().getSourcePort(), proxyMessage.getBody().getTargetHost(),
                            proxyMessage.getBody().getTargetPort());
            final TcpIoLoop tcpIoLoop = this.tcpIoLoops.get(ioLoopKey);
            if (tcpIoLoop == null) {
                PpaassLogger.INSTANCE
                        .error(() -> "Tcp loop not exist on TCP_CONNECT_SUCCESS, tcp loop key: {}",
                                () -> new Object[]{ioLoopKey});
                return;
            }
            PpaassLogger.INSTANCE
                    .debug(() -> "Success connect to {}:{}",
                            () -> new Object[]{tcpIoLoop.getDestinationAddress(), tcpIoLoop.getDestinationPort()});
            IpPacket synAck = TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildSynAck(
                    tcpIoLoop.getDestinationAddress().getAddress(),
                    tcpIoLoop.getDestinationPort(),
                    tcpIoLoop.getSourceAddress().getAddress(),
                    tcpIoLoop.getSourcePort(),
                    tcpIoLoop.getAccumulateRemoteToDeviceSequenceNumber(),
                    tcpIoLoop.getAccumulateRemoteToDeviceAcknowledgementNumber(),
                    tcpIoLoop.getMss()
            );
            TcpIoLoopRemoteToDeviceWriter.INSTANCE
                    .writeIpPacketToDevice(null, synAck, tcpIoLoop.getKey(),
                            remoteToDeviceStream);
            tcpIoLoop.increaseAccumulateRemoteToDeviceSequenceNumber(1);
            tcpIoLoop.setStatus(TcpIoLoopStatus.SYN_RECEIVED);
            return;
        }
        if (ProxyMessageBodyType.TCP_CONNECT_FAIL == proxyMessageBodyType) {
            String ioLoopKey = IoLoopUtil.INSTANCE
                    .generateIoLoopKey(IpDataProtocol.TCP, proxyMessage.getBody().getSourceHost(),
                            proxyMessage.getBody().getSourcePort(), proxyMessage.getBody().getTargetHost(),
                            proxyMessage.getBody().getTargetPort());
            final TcpIoLoop tcpIoLoop = this.tcpIoLoops.get(ioLoopKey);
            if (tcpIoLoop == null) {
                PpaassLogger.INSTANCE
                        .error(() -> "Tcp loop not exist on TCP_CONNECT_FAIL, tcp loop key: {}",
                                () -> new Object[]{ioLoopKey});
                return;
            }
            IpPacket ipPacketWroteToDevice =
                    TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildFinAck(
                            tcpIoLoop.getDestinationAddress().getAddress(),
                            tcpIoLoop.getDestinationPort(),
                            tcpIoLoop.getSourceAddress().getAddress(),
                            tcpIoLoop.getSourcePort(),
                            tcpIoLoop.getAccumulateRemoteToDeviceSequenceNumber(),
                            tcpIoLoop.getAccumulateRemoteToDeviceAcknowledgementNumber());
            TcpIoLoopRemoteToDeviceWriter.INSTANCE
                    .writeIpPacketToDevice(null, ipPacketWroteToDevice, tcpIoLoop.getKey(),
                            remoteToDeviceStream);
            Log.e(IoLoopRemoteToDeviceHandler.class.getName(),
                    "Fail to connect on tcp loop = " + tcpIoLoop);
            tcpIoLoop.destroy();
            return;
        }
        if (ProxyMessageBodyType.TCP_CONNECTION_CLOSE == proxyMessageBodyType) {
            String ioLoopKey = IoLoopUtil.INSTANCE
                    .generateIoLoopKey(IpDataProtocol.TCP, proxyMessage.getBody().getSourceHost(),
                            proxyMessage.getBody().getSourcePort(), proxyMessage.getBody().getTargetHost(),
                            proxyMessage.getBody().getTargetPort());
            final TcpIoLoop tcpIoLoop = this.tcpIoLoops.get(ioLoopKey);
            if (tcpIoLoop == null) {
                PpaassLogger.INSTANCE
                        .error(() -> "Tcp loop not exist on TCP_CONNECTION_CLOSE, tcp loop key: {}",
                                () -> new Object[]{ioLoopKey});
                return;
            }
            IpPacket ipPacketWroteToDevice =
                    TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildFinAck(
                            tcpIoLoop.getDestinationAddress().getAddress(),
                            tcpIoLoop.getDestinationPort(),
                            tcpIoLoop.getSourceAddress().getAddress(),
                            tcpIoLoop.getSourcePort(),
                            tcpIoLoop.getAccumulateRemoteToDeviceSequenceNumber(),
                            tcpIoLoop.getAccumulateRemoteToDeviceAcknowledgementNumber());
            TcpIoLoopRemoteToDeviceWriter.INSTANCE
                    .writeIpPacketToDevice(null, ipPacketWroteToDevice, tcpIoLoop.getKey(),
                            remoteToDeviceStream);
            Log.e(IoLoopRemoteToDeviceHandler.class.getName(),
                    "Close connect on tcp loop = " + tcpIoLoop);
            tcpIoLoop.destroy();
            return;
        }
        if (ProxyMessageBodyType.TCP_DATA_FAIL == proxyMessageBodyType) {
            String ioLoopKey = IoLoopUtil.INSTANCE
                    .generateIoLoopKey(IpDataProtocol.TCP, proxyMessage.getBody().getSourceHost(),
                            proxyMessage.getBody().getSourcePort(), proxyMessage.getBody().getTargetHost(),
                            proxyMessage.getBody().getTargetPort());
            final TcpIoLoop tcpIoLoop = this.tcpIoLoops.get(ioLoopKey);
            if (tcpIoLoop == null) {
                PpaassLogger.INSTANCE
                        .error(() -> "Tcp loop not exist on TCP_DATA_FAIL, tcp loop key: {}",
                                () -> new Object[]{ioLoopKey});
                return;
            }
            IpPacket ipPacketWroteToDevice =
                    TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildRst(
                            tcpIoLoop.getDestinationAddress().getAddress(),
                            tcpIoLoop.getDestinationPort(),
                            tcpIoLoop.getSourceAddress().getAddress(),
                            tcpIoLoop.getSourcePort(),
                            tcpIoLoop.getAccumulateRemoteToDeviceSequenceNumber(),
                            tcpIoLoop.getAccumulateRemoteToDeviceAcknowledgementNumber());
            TcpIoLoopRemoteToDeviceWriter.INSTANCE
                    .writeIpPacketToDevice(null, ipPacketWroteToDevice, tcpIoLoop.getKey(),
                            remoteToDeviceStream);
            Log.e(IoLoopRemoteToDeviceHandler.class.getName(),
                    "Fail to receive TCP data (FAIL_TCP) on tcp loop, reset connection, tcp loop = " + tcpIoLoop);
            tcpIoLoop.destroy();
            return;
        }
        if (ProxyMessageBodyType.TCP_DATA_SUCCESS == proxyMessageBodyType) {
            String ioLoopKey = IoLoopUtil.INSTANCE
                    .generateIoLoopKey(IpDataProtocol.TCP, proxyMessage.getBody().getSourceHost(),
                            proxyMessage.getBody().getSourcePort(), proxyMessage.getBody().getTargetHost(),
                            proxyMessage.getBody().getTargetPort());
            final TcpIoLoop tcpIoLoop = this.tcpIoLoops.get(ioLoopKey);
            if (tcpIoLoop == null) {
                PpaassLogger.INSTANCE
                        .error(() -> "Tcp loop not exist on TCP_DATA_SUCCESS, tcp loop key: {}",
                                () -> new Object[]{ioLoopKey});
                return;
            }
            PpaassLogger.INSTANCE
                    .debug(() -> "Success receive data from {}:{}, data:\n{}\n",
                            () -> new Object[]{tcpIoLoop.getDestinationAddress(), tcpIoLoop.getDestinationPort(),
                                    ByteBufUtil.prettyHexDump(
                                            Unpooled.wrappedBuffer(proxyMessage.getBody().getData()))});
            String remoteActionId = UUIDUtil.INSTANCE.generateUuid();
            tcpIoLoop.setUpdateTime(System.currentTimeMillis());
//            ByteBuf remoteMessageByteBuf = Unpooled.wrappedBuffer(proxyMessage.getBody().getData());
//            while (remoteMessageByteBuf.isReadable()) {
//                int length = tcpIoLoop.getMss();
//                if (remoteMessageByteBuf.readableBytes() < length) {
//                    length = remoteMessageByteBuf.readableBytes();
//                }
//                byte[] ackData = ByteBufUtil.getBytes(remoteMessageByteBuf.readBytes(length));
            long remoteToDeviceSequenceNumberBeforeIncrease = tcpIoLoop.getAccumulateRemoteToDeviceSequenceNumber();
            IpPacket ipPacketWroteToDevice =
                    TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildPshAck(
                            tcpIoLoop.getDestinationAddress().getAddress(),
                            tcpIoLoop.getDestinationPort(),
                            tcpIoLoop.getSourceAddress().getAddress(),
                            tcpIoLoop.getSourcePort(),
                            tcpIoLoop.getAccumulateRemoteToDeviceSequenceNumber(),
                            tcpIoLoop.getAccumulateRemoteToDeviceAcknowledgementNumber()
                            , proxyMessage.getBody().getData());
            TcpIoLoopRemoteToDeviceWriter.INSTANCE
                    .writeIpPacketToDevice(remoteActionId, ipPacketWroteToDevice, tcpIoLoop.getKey(),
                            remoteToDeviceStream);
            //Update sequence number after the data sent to device.
            tcpIoLoop.increaseAccumulateRemoteToDeviceSequenceNumber(proxyMessage.getBody().getData().length);
            PpaassLogger.INSTANCE
                    .debug(() -> "After send remote data to device [{}], RTD SEQUENCE before increase={}, tcp loop:\n{}\n",
                            () -> new Object[]{
                                    remoteActionId,
                                    remoteToDeviceSequenceNumberBeforeIncrease,
                                    tcpIoLoop
                            });
            return;
        }
        if (ProxyMessageBodyType.UDP_DATA_SUCCESS == proxyMessageBodyType) {
            byte[] originalDestinationAddressBytes =
                    InetAddress.getByName(proxyMessage.getBody().getTargetHost()).getAddress();
            byte[] originalSourceAddressBytes =
                    InetAddress.getByName(proxyMessage.getBody().getSourceHost()).getAddress();
            IpPacket udpPacketWriteToDevice = UdpIoLoopRemoteToDeviceWriter.INSTANCE
                    .buildUdpPacket(originalDestinationAddressBytes, proxyMessage.getBody().getTargetPort(),
                            originalSourceAddressBytes, proxyMessage.getBody().getSourcePort(),
                            proxyMessage.getBody().getData());
            UdpIoLoopRemoteToDeviceWriter.INSTANCE
                    .writeIpPacketToDevice(udpPacketWriteToDevice,
                            remoteToDeviceStream);
            PpaassLogger.INSTANCE
                    .debug(() -> "Success receive UDP data, proxy message:\n{}\n", () -> new Object[]{proxyMessage});
            return;
        }
        if (ProxyMessageBodyType.UDP_DATA_FAIL == proxyMessageBodyType) {
            PpaassLogger.INSTANCE
                    .error(() -> "Fail to receive UDP data, proxy message:\n{}\n", () -> new Object[]{proxyMessage});
            return;
        }
    }
}
