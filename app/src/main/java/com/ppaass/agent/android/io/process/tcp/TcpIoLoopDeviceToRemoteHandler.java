package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DuplexChannel;
import io.netty.util.ReferenceCountUtil;

import java.io.OutputStream;

@ChannelHandler.Sharable
public class TcpIoLoopDeviceToRemoteHandler extends ChannelDuplexHandler {
    public TcpIoLoopDeviceToRemoteHandler() {
    }
//    @Override
//    public void close(ChannelHandlerContext remoteChannelContext, ChannelPromise promise) throws Exception {
//        Channel remoteChannel = remoteChannelContext.channel();
//        final TcpIoLoop tcpIoLoop = remoteChannel.attr(ITcpIoLoopConstant.TCP_LOOP).get();
//        final OutputStream remoteToDeviceStream = remoteChannel.attr(ITcpIoLoopConstant.REMOTE_TO_DEVICE_STREAM).get();
//        tcpIoLoop.setStatus(TcpIoLoopStatus.FIN_WAITE1);
//        Log.d(TcpIoLoopDeviceToRemoteHandler.class.getName(),
//                "Close tcp loop as remote channel closed, tcp loop = " + tcpIoLoop);
//        TcpIoLoopOutputWriter.INSTANCE.writeFin(tcpIoLoop, remoteToDeviceStream);
//    }

    @Override
    public void channelRead(ChannelHandlerContext remoteChannelContext, Object remoteMessage)
            throws Exception {
        DuplexChannel remoteChannel = (DuplexChannel) remoteChannelContext.channel();
        final TcpIoLoop tcpIoLoop = remoteChannel.attr(ITcpIoLoopConstant.TCP_LOOP).get();
        final OutputStream remoteToDeviceStream = remoteChannel.attr(ITcpIoLoopConstant.REMOTE_TO_DEVICE_STREAM).get();
        ByteBuf remoteMessageByteBuf = (ByteBuf) remoteMessage;
        int currentRemoteMessagePacketLength = remoteMessageByteBuf.readableBytes();
        Log.v(TcpIoLoopDeviceToRemoteHandler.class.getName(),
                "Current remote message packet size = " + currentRemoteMessagePacketLength + ", buffer capacity = " +
                        remoteMessageByteBuf.capacity() + ", tcp loop = " +
                        tcpIoLoop);
        while (remoteMessageByteBuf.isReadable()) {
            int length = tcpIoLoop.getMss();
            if (remoteMessageByteBuf.readableBytes() < length) {
                length = remoteMessageByteBuf.readableBytes();
            }
            byte[] ackData = ByteBufUtil.getBytes(remoteMessageByteBuf.readBytes(length));
            TcpIoLoopOutputWriter.INSTANCE.writePshAck(tcpIoLoop, ackData, remoteToDeviceStream);
        }
        if (currentRemoteMessagePacketLength < remoteMessageByteBuf.capacity()) {
            tcpIoLoop.setStatus(TcpIoLoopStatus.FIN_WAITE1);
            Log.d(TcpIoLoopDeviceToRemoteHandler.class.getName(),
                    "Close tcp loop as remote channel no more data, tcp loop = " + tcpIoLoop);
            TcpIoLoopOutputWriter.INSTANCE.writeFinAck(tcpIoLoop, remoteToDeviceStream);
        }
        ReferenceCountUtil.release(remoteMessage);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext remoteChannelContext, Throwable cause) throws Exception {
        Channel remoteChannel = remoteChannelContext.channel();
        final TcpIoLoop tcpIoLoop = remoteChannel.attr(ITcpIoLoopConstant.TCP_LOOP).get();
        Log.e(TcpIoLoopDeviceToRemoteHandler.class.getName(), "Exception happen on tcp loop, tcp loop =  " + tcpIoLoop,
                cause);
    }
}
