package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import com.ppaass.common.message.MessageSerializer;
import com.ppaass.common.message.ProxyMessage;
import com.ppaass.common.message.ProxyMessageBodyType;
import com.ppaass.protocol.base.ip.IpPacket;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.io.OutputStream;
import java.util.concurrent.ConcurrentMap;

@ChannelHandler.Sharable
public class TcpIoLoopRemoteToDeviceHandler extends SimpleChannelInboundHandler<ProxyMessage> {
    private final OutputStream remoteToDeviceStream;
    private final ConcurrentMap<String, TcpIoLoop> tcpIoLoops;

    public TcpIoLoopRemoteToDeviceHandler(OutputStream remoteToDeviceStream,
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
            Log.e(TcpIoLoopRemoteToDeviceHandler.class.getName(), "Tcp loop not exist, tcp loop key = "+proxyMessage.getBody().getId());
            return;
        }
        if (ProxyMessageBodyType.CONNECT_SUCCESS == proxyMessageBodyType) {
            Log.d(TcpIoLoopRemoteToDeviceHandler.class.getName(),
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
        if (ProxyMessageBodyType.CONNECT_FAIL == proxyMessageBodyType) {
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
            Log.e(TcpIoLoopRemoteToDeviceHandler.class.getName(),
                    "Fail to connect on tcp loop = " + tcpIoLoop);
            tcpIoLoop.destroy();
            return;
        }
        if (ProxyMessageBodyType.CONNECTION_CLOSE == proxyMessageBodyType) {
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
            Log.e(TcpIoLoopRemoteToDeviceHandler.class.getName(),
                    "Close connect on tcp loop = " + tcpIoLoop);
            tcpIoLoop.destroy();
            return;
        }
        if (ProxyMessageBodyType.FAIL_TCP == proxyMessageBodyType) {
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
            Log.e(TcpIoLoopRemoteToDeviceHandler.class.getName(),
                    "Fail to receive TCP data (FAIL_TCP) on tcp loop, reset connection, tcp loop = " + tcpIoLoop);
            tcpIoLoop.destroy();
            return;
        }
        if (ProxyMessageBodyType.FAIL_UDP == proxyMessageBodyType) {
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
            Log.e(TcpIoLoopRemoteToDeviceHandler.class.getName(),
                    "Should not receive UDP data (FAIL_UDP) on tcp loop = " + tcpIoLoop);
            tcpIoLoop.destroy();
            return;
        }
        if (ProxyMessageBodyType.OK_TCP == proxyMessageBodyType) {
            Log.d(TcpIoLoopRemoteToDeviceHandler.class.getName(),
                    "Success receive data from [" + tcpIoLoop.getDestinationAddress() + ":" +
                            tcpIoLoop.getDestinationPort() +
                            "], data:\n" +
                            ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(proxyMessage.getBody().getData())));
            String remoteActionId = MessageSerializer.INSTANCE.generateUuid();
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
                Log.d(TcpIoLoopRemoteToDeviceHandler.class.getName(),
                        "After send remote data to device [" + remoteActionId + "], RTD SEQUENCE before increase = " +
                                remoteToDeviceSequenceNumberBeforeIncrease + ", tcp loop = " + tcpIoLoop);
//            }
            return;
        }
        if (ProxyMessageBodyType.OK_UDP == proxyMessageBodyType) {
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
            Log.e(TcpIoLoopRemoteToDeviceHandler.class.getName(),
                    "Should not receive UDP data on tcp loop = " + tcpIoLoop);
            tcpIoLoop.destroy();
        }
    }
}
