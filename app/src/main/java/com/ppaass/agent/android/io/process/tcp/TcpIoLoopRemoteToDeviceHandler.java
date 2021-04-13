package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import com.ppaass.common.message.MessageSerializer;
import com.ppaass.common.message.ProxyMessage;
import com.ppaass.common.message.ProxyMessageBodyType;
import com.ppaass.protocol.base.ip.IpPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.io.OutputStream;

@ChannelHandler.Sharable
public class TcpIoLoopRemoteToDeviceHandler extends SimpleChannelInboundHandler<ProxyMessage> {
    public TcpIoLoopRemoteToDeviceHandler() {
    }

    @Override
    public void channelInactive(ChannelHandlerContext remoteChannelContext) throws Exception {
        Channel remoteChannel = remoteChannelContext.channel();
        final TcpIoLoop tcpIoLoop = remoteChannel.attr(ITcpIoLoopConstant.TCP_LOOP).get();
        final OutputStream remoteToDeviceStream = remoteChannel.attr(ITcpIoLoopConstant.REMOTE_TO_DEVICE_STREAM).get();
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
        Log.d(TcpIoLoopRemoteToDeviceHandler.class.getName(),
                "Remote connection closed, reset device connection, tcp loop=" +
                        tcpIoLoop);
    }

    @Override
    public void channelRead0(ChannelHandlerContext proxyChannelContext, ProxyMessage proxyMessage)
            throws Exception {
        Channel proxyChannel = proxyChannelContext.channel();
        final TcpIoLoop tcpIoLoop = proxyChannel.attr(ITcpIoLoopConstant.TCP_LOOP).get();
        final OutputStream remoteToDeviceStream = proxyChannel.attr(ITcpIoLoopConstant.REMOTE_TO_DEVICE_STREAM).get();
        if (tcpIoLoop == null || remoteToDeviceStream == null) {
            proxyChannel.close();
            return;
        }
        ProxyMessageBodyType proxyMessageBodyType = proxyMessage.getBody().getBodyType();
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
            Log.e(TcpIoLoopFlowProcessor.class.getName(),
                    "Fail to connect on tcp loop = " + tcpIoLoop);
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
            Log.e(TcpIoLoopFlowProcessor.class.getName(),
                    "Fail to receive TCP data (FAIL_TCP) on tcp loop, reset connection, tcp loop = " + tcpIoLoop);
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
            Log.e(TcpIoLoopFlowProcessor.class.getName(),
                    "Should not receive UDP data (FAIL_UDP) on tcp loop = " + tcpIoLoop);
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
            ByteBuf remoteMessageByteBuf = Unpooled.wrappedBuffer(proxyMessage.getBody().getData());
            while (remoteMessageByteBuf.isReadable()) {
                int length = tcpIoLoop.getMss();
                if (remoteMessageByteBuf.readableBytes() < length) {
                    length = remoteMessageByteBuf.readableBytes();
                }
                byte[] ackData = ByteBufUtil.getBytes(remoteMessageByteBuf.readBytes(length));
                long remoteToDeviceSequenceNumberBeforeIncrease = tcpIoLoop.getAccumulateRemoteToDeviceSequenceNumber();
                IpPacket ipPacketWroteToDevice =
                        TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildPshAck(
                                tcpIoLoop.getDestinationAddress().getAddress(),
                                tcpIoLoop.getDestinationPort(),
                                tcpIoLoop.getSourceAddress().getAddress(),
                                tcpIoLoop.getSourcePort(),
                                tcpIoLoop.getAccumulateRemoteToDeviceSequenceNumber(),
                                tcpIoLoop.getAccumulateRemoteToDeviceAcknowledgementNumber()
                                , ackData);
                TcpIoLoopRemoteToDeviceWriter.INSTANCE
                        .writeIpPacketToDevice(remoteActionId, ipPacketWroteToDevice, tcpIoLoop.getKey(),
                                remoteToDeviceStream);
                //Update sequence number after the data sent to device.
                tcpIoLoop.increaseAccumulateRemoteToDeviceSequenceNumber(length);
                Log.d(TcpIoLoopFlowProcessor.class.getName(),
                        "After send remote data to device [" + remoteActionId + "], RTD SEQUENCE before increase = " +
                                remoteToDeviceSequenceNumberBeforeIncrease + ", tcp loop = " + tcpIoLoop);
            }
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
            Log.e(TcpIoLoopFlowProcessor.class.getName(),
                    "Should not receive UDP data on tcp loop = " + tcpIoLoop);
            return;
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext remoteChannelContext, Throwable cause) throws Exception {
        Channel remoteChannel = remoteChannelContext.channel();
        final TcpIoLoop tcpIoLoop = remoteChannel.attr(ITcpIoLoopConstant.TCP_LOOP).get();
        final OutputStream remoteToDeviceStream = remoteChannel.attr(ITcpIoLoopConstant.REMOTE_TO_DEVICE_STREAM).get();
        Log.e(TcpIoLoopRemoteToDeviceHandler.class.getName(),
                "Exception for tcp loop remote channel, tcp loop=" + tcpIoLoop, cause);
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
        tcpIoLoop.destroy();
    }
}
