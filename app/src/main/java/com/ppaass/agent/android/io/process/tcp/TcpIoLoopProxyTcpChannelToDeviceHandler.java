package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import com.ppaass.protocol.base.ip.IpPacket;
import com.ppaass.protocol.common.util.UUIDUtil;
import com.ppaass.protocol.vpn.message.ProxyMessage;
import com.ppaass.protocol.vpn.message.ProxyMessageBodyType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.apache.commons.pool2.DestroyMode;
import org.apache.commons.pool2.impl.GenericObjectPool;

import java.io.OutputStream;

@ChannelHandler.Sharable
public class TcpIoLoopProxyTcpChannelToDeviceHandler extends SimpleChannelInboundHandler<ProxyMessage> {
    public TcpIoLoopProxyTcpChannelToDeviceHandler() {
    }

    @Override
    public void channelInactive(ChannelHandlerContext proxyTcpChannelContext) throws Exception {
        Channel proxyTcpChannel = proxyTcpChannelContext.channel();
        final TcpIoLoop tcpIoLoop = proxyTcpChannel.attr(ITcpIoLoopConstant.IProxyTcpChannelAttr.TCP_LOOP).get();
        final OutputStream remoteToDeviceStream =
                proxyTcpChannel.attr(ITcpIoLoopConstant.IProxyTcpChannelAttr.REMOTE_TO_DEVICE_STREAM).get();
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
        GenericObjectPool<Channel> channelPool =
                proxyTcpChannel.attr(ITcpIoLoopConstant.IProxyTcpChannelAttr.CHANNEL_POOL).get();
        proxyTcpChannel.attr(ITcpIoLoopConstant.IProxyTcpChannelAttr.CLOSED_ALREADY).set(true);
        channelPool.invalidateObject(proxyTcpChannel, DestroyMode.ABANDONED);
        Log.d(TcpIoLoopProxyTcpChannelToDeviceHandler.class.getName(),
                "Remote connection closed, reset device connection, tcp loop=" +
                        tcpIoLoop);
    }

    @Override
    public void channelRead0(ChannelHandlerContext proxyTcpChannelContext, ProxyMessage proxyMessage)
            throws Exception {
        Channel proxyTcpChannel = proxyTcpChannelContext.channel();
        final TcpIoLoop tcpIoLoop = proxyTcpChannel.attr(ITcpIoLoopConstant.IProxyTcpChannelAttr.TCP_LOOP).get();
        final OutputStream remoteToDeviceStream =
                proxyTcpChannel.attr(ITcpIoLoopConstant.IProxyTcpChannelAttr.REMOTE_TO_DEVICE_STREAM).get();
        if (tcpIoLoop == null || remoteToDeviceStream == null) {
            GenericObjectPool<Channel> channelPool =
                    proxyTcpChannel.attr(ITcpIoLoopConstant.IProxyTcpChannelAttr.CHANNEL_POOL).get();
            channelPool.returnObject(proxyTcpChannel);
            return;
        }
        ProxyMessageBodyType proxyMessageBodyType = proxyMessage.getBody().getBodyType();
        if (ProxyMessageBodyType.TCP_CONNECT_SUCCESS == proxyMessageBodyType) {
            Log.d(TcpIoLoopProxyTcpChannelToDeviceHandler.class.getName(),
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
            GenericObjectPool<Channel> channelPool =
                    proxyTcpChannel.attr(ITcpIoLoopConstant.IProxyTcpChannelAttr.CHANNEL_POOL).get();
            channelPool.returnObject(proxyTcpChannel);
            Log.e(TcpIoLoopFlowProcessor.class.getName(),
                    "Fail to connect on tcp loop = " + tcpIoLoop);
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
            GenericObjectPool<Channel> channelPool =
                    proxyTcpChannel.attr(ITcpIoLoopConstant.IProxyTcpChannelAttr.CHANNEL_POOL).get();
            channelPool.returnObject(proxyTcpChannel);
            Log.e(TcpIoLoopFlowProcessor.class.getName(),
                    "Fail to receive TCP data (FAIL_TCP) on tcp loop, reset connection, tcp loop = " + tcpIoLoop);
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
            GenericObjectPool<Channel> channelPool =
                    proxyTcpChannel.attr(ITcpIoLoopConstant.IProxyTcpChannelAttr.CHANNEL_POOL).get();
            channelPool.returnObject(proxyTcpChannel);
            Log.e(TcpIoLoopFlowProcessor.class.getName(),
                    "Should not receive UDP data (FAIL_UDP) on tcp loop = " + tcpIoLoop);
            return;
        }
        if (ProxyMessageBodyType.TCP_DATA_SUCCESS == proxyMessageBodyType) {
            Log.d(TcpIoLoopProxyTcpChannelToDeviceHandler.class.getName(),
                    "Success receive data from [" + tcpIoLoop.getDestinationAddress() + ":" +
                            tcpIoLoop.getDestinationPort() +
                            "], data:\n" +
                            ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(proxyMessage.getBody().getData())));
            String remoteActionId = UUIDUtil.INSTANCE.generateUuid();
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
        if (ProxyMessageBodyType.UDP_DATA_SUCCESS == proxyMessageBodyType) {
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
    public void exceptionCaught(ChannelHandlerContext proxyTcpChannelContext, Throwable cause) throws Exception {
        Channel proxyTcpChannel = proxyTcpChannelContext.channel();
        final TcpIoLoop tcpIoLoop = proxyTcpChannel.attr(ITcpIoLoopConstant.IProxyTcpChannelAttr.TCP_LOOP).get();
        final OutputStream remoteToDeviceStream =
                proxyTcpChannel.attr(ITcpIoLoopConstant.IProxyTcpChannelAttr.REMOTE_TO_DEVICE_STREAM).get();
        Log.e(TcpIoLoopProxyTcpChannelToDeviceHandler.class.getName(),
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
