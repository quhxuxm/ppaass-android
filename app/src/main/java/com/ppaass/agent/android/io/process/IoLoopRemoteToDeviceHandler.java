package com.ppaass.agent.android.io.process;

import android.util.Log;
import com.ppaass.protocol.base.ip.IpPacket;
import com.ppaass.protocol.common.util.UUIDUtil;
import com.ppaass.protocol.vpn.message.ProxyMessage;
import com.ppaass.protocol.vpn.message.ProxyMessageBodyType;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
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
        Channel proxyChannel = proxyChannelContext.channel();
        ProxyMessageBodyType proxyMessageBodyType = proxyMessage.getBody().getBodyType();
        final TcpIoLoop tcpIoLoop = this.tcpIoLoops.get(proxyMessage.getBody().getId());
        if (tcpIoLoop == null) {
            Log.e(IoLoopRemoteToDeviceHandler.class.getName(),
                    "Tcp loop not exist, tcp loop key = " + proxyMessage.getBody().getId());
            return;
        }
        if (ProxyMessageBodyType.TCP_CONNECT_SUCCESS == proxyMessageBodyType) {
            Log.d(IoLoopRemoteToDeviceHandler.class.getName(),
                    "Success connect to [" + tcpIoLoop.getDestinationAddress() + ":" + tcpIoLoop.getDestinationPort() +
                            "]");
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
        if (ProxyMessageBodyType.UDP_DATA_FAIL == proxyMessageBodyType) {
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
                    "Should not receive UDP data (FAIL_UDP) on tcp loop = " + tcpIoLoop);
            tcpIoLoop.destroy();
            return;
        }
        if (ProxyMessageBodyType.TCP_DATA_SUCCESS == proxyMessageBodyType) {
            Log.d(IoLoopRemoteToDeviceHandler.class.getName(),
                    "Success receive data from [" + tcpIoLoop.getDestinationAddress() + ":" +
                            tcpIoLoop.getDestinationPort() +
                            "], data:\n" +
                            ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(proxyMessage.getBody().getData())));
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
            Log.d(IoLoopRemoteToDeviceHandler.class.getName(),
                    "After send remote data to device [" + remoteActionId + "], RTD SEQUENCE before increase = " +
                            remoteToDeviceSequenceNumberBeforeIncrease + ", tcp loop = " + tcpIoLoop);
//            }
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
            Log.e(IoLoopRemoteToDeviceHandler.class.getName(),
                    "Should not receive UDP data on tcp loop = " + tcpIoLoop);
            tcpIoLoop.destroy();
        }
    }
}
