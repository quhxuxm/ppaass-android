package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;

import java.io.OutputStream;

@ChannelHandler.Sharable
public class TcpIoLoopTargetToVpnHandler extends ChannelDuplexHandler {
    public TcpIoLoopTargetToVpnHandler() {
    }

    @Override
    public void close(ChannelHandlerContext remoteChannelContext, ChannelPromise promise) throws Exception {
        Channel remoteChannel = remoteChannelContext.channel();
        final TcpIoLoop tcpIoLoop = remoteChannel.attr(ITcpIoLoopConstant.TCP_LOOP).get();
        final OutputStream remoteToDeviceStream = remoteChannel.attr(ITcpIoLoopConstant.REMOTE_TO_DEVICE_STREAM).get();
        Log.d(TcpIoLoopTargetToVpnHandler.class.getName(),
                "Close tcp loop as remote channel closed, tcp loop = " + tcpIoLoop);
        TcpIoLoopOutputWriter.INSTANCE.writeFin(tcpIoLoop, remoteToDeviceStream);
    }
//    @Override
//    public void channelReadComplete(ChannelHandlerContext targetChannelContext) throws Exception {
//        Channel targetChannel = targetChannelContext.channel();
//        final TcpIoLoop tcpIoLoop = targetChannel.attr(IIoConstant.TCP_LOOP).get();
//        tcpIoLoop.switchStatus(TcpIoLoopStatus.FIN_WAITE1);
//        tcpIoLoop.setVpnToAppSequenceNumber(tcpIoLoop.getVpnToAppSequenceNumber() + 1);
//        TcpIoLoopOutputWriter.INSTANCE.writeFinForTcpIoLoop(tcpIoLoop);
//    }

    @Override
    public void channelRead(ChannelHandlerContext remoteChannelContext, Object remoteMessage)
            throws Exception {
        Channel remoteChannel = remoteChannelContext.channel();
        final TcpIoLoop tcpIoLoop = remoteChannel.attr(ITcpIoLoopConstant.TCP_LOOP).get();
        final OutputStream remoteToDeviceStream = remoteChannel.attr(ITcpIoLoopConstant.REMOTE_TO_DEVICE_STREAM).get();
        ByteBuf remoteMessageByteBuf = (ByteBuf) remoteMessage;
        while (remoteMessageByteBuf.isReadable()) {
            int length = tcpIoLoop.getMss();
            if (remoteMessageByteBuf.readableBytes() < length) {
                length = remoteMessageByteBuf.readableBytes();
            }
            byte[] ackData = ByteBufUtil.getBytes(remoteMessageByteBuf.readBytes(length));
            TcpIoLoopOutputWriter.INSTANCE.writePshAck(tcpIoLoop, ackData, remoteToDeviceStream);
            tcpIoLoop.offerWaitingDeviceSeq(tcpIoLoop.getCurrentRemoteToDeviceAck());
        }
        ReferenceCountUtil.release(remoteMessage);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext remoteChannelContext, Throwable cause) throws Exception {
        Channel remoteChannel = remoteChannelContext.channel();
        final TcpIoLoop tcpIoLoop = remoteChannel.attr(ITcpIoLoopConstant.TCP_LOOP).get();
        Log.e(TcpIoLoopTargetToVpnHandler.class.getName(), "Exception happen on tcp loop, tcp loop =  " + tcpIoLoop,
                cause);
    }
}
