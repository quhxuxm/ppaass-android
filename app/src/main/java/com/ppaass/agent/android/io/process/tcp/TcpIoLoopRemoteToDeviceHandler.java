package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import com.ppaass.agent.android.io.protocol.ip.IpPacket;
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
//        if (tcpIoLoop.getStatus() != TcpIoLoopStatus.ESTABLISHED) {
//            Log.d(TcpIoLoopRemoteToDeviceHandler.class.getName(),
//                    "Remote channel closed, and tcp loop also NOT in ESTABLISHED, nothing to do, tcp loop =" +
//                            tcpIoLoop);
//            return;
//        }
//        IpPacket ipPacketWroteToDevice =
//                TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildFin(
//                        tcpIoLoop.getDestinationAddress().getAddress(),
//                        tcpIoLoop.getDestinationPort(),
//                        tcpIoLoop.getSourceAddress().getAddress(),
//                        tcpIoLoop.getSourcePort(),
//                        tcpIoLoop.getAccumulateRemoteToDeviceSequenceNumber(),
//                        tcpIoLoop.getAccumulateRemoteToDeviceAcknowledgementNumber());
//        TcpIoLoopRemoteToDeviceWriter.INSTANCE
//                .writeIpPacketToDevice(ipPacketWroteToDevice, tcpIoLoop.getKey(),
//                        remoteToDeviceStream);
//        tcpIoLoop.setStatus(TcpIoLoopStatus.FIN_WAITE1);
//        Log.e(TcpIoLoopRemoteToDeviceHandler.class.getName(),
//                "Remote connection closed, but tcp loop still in ESTABLISHED, close the tcp loop and switch status to FIN_WAITE1, tcp loop=" +
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
        Channel remoteChannel = remoteChannelContext.channel();
        final TcpIoLoop tcpIoLoop = remoteChannel.attr(ITcpIoLoopConstant.TCP_LOOP).get();
        final OutputStream remoteToDeviceStream = remoteChannel.attr(ITcpIoLoopConstant.REMOTE_TO_DEVICE_STREAM).get();
        tcpIoLoop.setUpdateTime(System.currentTimeMillis());
        ByteBuf remoteMessageByteBuf = (ByteBuf) remoteMessage;
        while (remoteMessageByteBuf.isReadable()) {
            int length = tcpIoLoop.getMss();
            if (remoteMessageByteBuf.readableBytes() < length) {
                length = remoteMessageByteBuf.readableBytes();
            }
            byte[] ackData = ByteBufUtil.getBytes(remoteMessageByteBuf.readBytes(length));
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
            synchronized (tcpIoLoop) {
                tcpIoLoop.getTcpWindow()
                        .put(tcpIoLoop.getAccumulateRemoteToDeviceSequenceNumber() + length, windowIpPacketWrapper);
            }
            TcpIoLoopRemoteToDeviceWriter.INSTANCE
                    .writeIpPacketToDevice(ipPacketWroteToDevice, tcpIoLoop.getKey(),
                            remoteToDeviceStream);
            //Update sequence number after the data sent to device.
            tcpIoLoop.increaseAccumulateRemoteToDeviceSequenceNumber(length);
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
        synchronized (tcpIoLoop) {
            if (tcpIoLoop.getTcpWindow().size() == 0) {
                remoteChannel.read();
            }
        }
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
                    .writeIpPacketToDevice(ipPacketWroteToDevice, tcpIoLoop.getKey(),
                            remoteToDeviceStream);
            tcpIoLoop.destroy();
        }
    }
}
