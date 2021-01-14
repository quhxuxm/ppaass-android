package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import com.ppaass.agent.android.io.protocol.ip.IpPacket;
import com.ppaass.kt.common.SerializerKt;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

import java.io.OutputStream;

@ChannelHandler.Sharable
public class TcpIoLoopRemoteToDeviceHandler extends ChannelInboundHandlerAdapter {
    public TcpIoLoopRemoteToDeviceHandler() {
    }
//    @Override
//    public void channelInactive(ChannelHandlerContext remoteChannelContext) throws Exception {
//        Channel remoteChannel = remoteChannelContext.channel();
//        final TcpIoLoop tcpIoLoop = remoteChannel.attr(ITcpIoLoopConstant.TCP_LOOP).get();
//        final OutputStream remoteToDeviceStream = remoteChannel.attr(ITcpIoLoopConstant.REMOTE_TO_DEVICE_STREAM).get();
//        IpPacket ipPacketWroteToDevice =
//                TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildFinAck(
//                        tcpIoLoop.getDestinationAddress().getAddress(),
//                        tcpIoLoop.getDestinationPort(),
//                        tcpIoLoop.getSourceAddress().getAddress(),
//                        tcpIoLoop.getSourcePort(),
//                        tcpIoLoop.getAccumulateRemoteToDeviceSequenceNumber(),
//                        tcpIoLoop.getAccumulateRemoteToDeviceAcknowledgementNumber());
//        TcpIoLoopRemoteToDeviceWriter.INSTANCE
//                .writeIpPacketToDevice(null, ipPacketWroteToDevice, tcpIoLoop.getKey(),
//                        remoteToDeviceStream);
//        Log.d(TcpIoLoopRemoteToDeviceHandler.class.getName(),
//                "Remote connection closed, close device connection, tcp loop=" +
//                        tcpIoLoop);
//    }

    @Override
    public void channelActive(ChannelHandlerContext remoteChannelContext) throws Exception {
        Channel remoteChannel = remoteChannelContext.channel();
        remoteChannel.read();
    }

    @Override
    public void channelRead(ChannelHandlerContext remoteChannelContext, Object remoteMessage)
            throws Exception {
        String remoteActionId = SerializerKt.generateUuid();
        Channel remoteChannel = remoteChannelContext.channel();
        final TcpIoLoop tcpIoLoop = remoteChannel.attr(ITcpIoLoopConstant.TCP_LOOP).get();
        final OutputStream remoteToDeviceStream = remoteChannel.attr(ITcpIoLoopConstant.REMOTE_TO_DEVICE_STREAM).get();
        tcpIoLoop.setUpdateTime(System.currentTimeMillis());
        ByteBuf remoteMessageByteBuf = (ByteBuf) remoteMessage;
        while (remoteMessageByteBuf.isReadable()) {
            TcpIoLoop.TcpIoLoopWindowIpPacketWrapper previousWindowElement = tcpIoLoop.getTcpWindow().peek();
            if (previousWindowElement != null) {
                long sequenceToConfirm = previousWindowElement.getSequenceNumber();
                if (sequenceToConfirm < tcpIoLoop.getAccumulateRemoteToDeviceSequenceNumber()) {
                    synchronized (tcpIoLoop) {
                        tcpIoLoop.wait();
                    }
                }
            }
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
            TcpIoLoop.TcpIoLoopWindowIpPacketWrapper windowIpPacketWrapper =
                    new TcpIoLoop.TcpIoLoopWindowIpPacketWrapper(ipPacketWroteToDevice, System.currentTimeMillis());
            tcpIoLoop.getTcpWindow().offer(windowIpPacketWrapper);
            TcpIoLoopRemoteToDeviceWriter.INSTANCE
                    .writeIpPacketToDevice(remoteActionId, ipPacketWroteToDevice, tcpIoLoop.getKey(),
                            remoteToDeviceStream);
            //Update sequence number after the data sent to device.
            tcpIoLoop.increaseAccumulateRemoteToDeviceSequenceNumber(length);
            Log.d(TcpIoLoopFlowProcessor.class.getName(),
                    "After send remote data to device [" + remoteActionId + "], RTD SEQUENCE before increase = " +
                            remoteToDeviceSequenceNumberBeforeIncrease + ", tcp loop = " + tcpIoLoop);
        }
        ReferenceCountUtil.release(remoteMessage);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext remoteChannelContext) throws Exception {
        Channel remoteChannel = remoteChannelContext.channel();
        final TcpIoLoop tcpIoLoop = remoteChannel.attr(ITcpIoLoopConstant.TCP_LOOP).get();
        if (tcpIoLoop == null) {
            return;
        }
        tcpIoLoop.getRemoteChannel().read();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext remoteChannelContext, Throwable cause) throws Exception {
        Channel remoteChannel = remoteChannelContext.channel();
        final TcpIoLoop tcpIoLoop = remoteChannel.attr(ITcpIoLoopConstant.TCP_LOOP).get();
        final OutputStream remoteToDeviceStream = remoteChannel.attr(ITcpIoLoopConstant.REMOTE_TO_DEVICE_STREAM).get();
        Log.e(TcpIoLoopRemoteToDeviceHandler.class.getName(),
                "Exception for tcp loop remote channel, tcp loop=" + tcpIoLoop, cause);
        if (cause.getMessage() != null && cause.getMessage().contains("Connection reset by peer")) {
            Log.e(TcpIoLoopRemoteToDeviceHandler.class.getName(),
                    "Connection reset, tcp loop=" + tcpIoLoop);
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
}
