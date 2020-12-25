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
public class TcpIoLoopRemoteToDeviceHandler extends ChannelDuplexHandler {
    public TcpIoLoopRemoteToDeviceHandler() {
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
        final TcpIoLoopInfo tcpIoLoopInfo = remoteChannel.attr(ITcpIoLoopConstant.TCP_LOOP).get();
        final OutputStream remoteToDeviceStream = remoteChannel.attr(ITcpIoLoopConstant.REMOTE_TO_DEVICE_STREAM).get();
        ByteBuf remoteMessageByteBuf = (ByteBuf) remoteMessage;
        int currentRemoteMessagePacketLength = remoteMessageByteBuf.readableBytes();
        Log.v(TcpIoLoopRemoteToDeviceHandler.class.getName(),
                "Current remote message packet size = " + currentRemoteMessagePacketLength + ", buffer capacity = " +
                        remoteMessageByteBuf.capacity() + ", tcp loop = " +
                        tcpIoLoopInfo);
//        int sentBytes = 0;
        while (remoteMessageByteBuf.isReadable()) {
            tcpIoLoopInfo.getAckSemaphore().acquire();
            int length = tcpIoLoopInfo.getMss();
            if (remoteMessageByteBuf.readableBytes() < length) {
                length = remoteMessageByteBuf.readableBytes();
            }
            byte[] ackData = ByteBufUtil.getBytes(remoteMessageByteBuf.readBytes(length));
//            if ((sentBytes + length) >= tcpIoLoop.getWindow()) {
//                tcpIoLoop.getAckSemaphore().acquire();
//                sentBytes = length;
//            } else {
//                sentBytes += length;
//            }
            TcpIoLoopOutputWriter.INSTANCE.writePshAck(tcpIoLoopInfo, ackData, remoteToDeviceStream);
        }
        if (currentRemoteMessagePacketLength < remoteMessageByteBuf.capacity()) {
            tcpIoLoopInfo.getAckSemaphore().acquire();
            tcpIoLoopInfo.setStatus(TcpIoLoopStatus.FIN_WAITE1);
            Log.d(TcpIoLoopRemoteToDeviceHandler.class.getName(),
                    "Close tcp loop as remote channel no more data, tcp loop = " + tcpIoLoopInfo);
            TcpIoLoopOutputWriter.INSTANCE.writeFinAck(tcpIoLoopInfo, remoteToDeviceStream);
        }
        ReferenceCountUtil.release(remoteMessage);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext remoteChannelContext, Throwable cause) throws Exception {
        Channel remoteChannel = remoteChannelContext.channel();
        final TcpIoLoopInfo tcpIoLoopInfo = remoteChannel.attr(ITcpIoLoopConstant.TCP_LOOP).get();
        Log.e(TcpIoLoopRemoteToDeviceHandler.class.getName(), "Exception happen on tcp loop, tcp loop =  " +
                        tcpIoLoopInfo,
                cause);
    }
}
