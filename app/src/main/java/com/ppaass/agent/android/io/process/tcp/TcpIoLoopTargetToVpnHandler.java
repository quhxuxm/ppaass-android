package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import com.ppaass.agent.android.io.process.IIoConstant;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;

@ChannelHandler.Sharable
public class TcpIoLoopTargetToVpnHandler extends ChannelDuplexHandler {
    public TcpIoLoopTargetToVpnHandler() {
    }

//    @Override
//    public void close(ChannelHandlerContext targetChannelContext, ChannelPromise promise) throws Exception {
//        Channel targetChannel = targetChannelContext.channel();
//        final TcpIoLoop tcpIoLoop = targetChannel.attr(IIoConstant.TCP_LOOP).get();
//        tcpIoLoop.switchStatus(TcpIoLoopStatus.FIN_WAITE1);
//        tcpIoLoop.setVpnToAppSequenceNumber(tcpIoLoop.getVpnToAppSequenceNumber() + 1);
//        Log.d(TcpIoLoopTargetToVpnHandler.class.getName(),
//                "Close tcp loop as target channel closed, tcp loop = " + tcpIoLoop);
//        TcpIoLoopOutputWriter.INSTANCE.writeFinForTcpIoLoop(tcpIoLoop);
//        tcpIoLoop.getAckSemaphore().release();
//    }

//    @Override
//    public void channelReadComplete(ChannelHandlerContext targetChannelContext) throws Exception {
//        Channel targetChannel = targetChannelContext.channel();
//        final TcpIoLoop tcpIoLoop = targetChannel.attr(IIoConstant.TCP_LOOP).get();
//        tcpIoLoop.switchStatus(TcpIoLoopStatus.FIN_WAITE1);
//        tcpIoLoop.setVpnToAppSequenceNumber(tcpIoLoop.getVpnToAppSequenceNumber() + 1);
//        TcpIoLoopOutputWriter.INSTANCE.writeFinForTcpIoLoop(tcpIoLoop);
//    }

    @Override
    public void channelRead(ChannelHandlerContext targetChannelContext, Object targetMessage)
            throws Exception {
        Channel targetChannel = targetChannelContext.channel();
        final TcpIoLoop tcpIoLoop = targetChannel.attr(IIoConstant.TCP_LOOP).get();
        ByteBuf targetMessageByteBuf = (ByteBuf) targetMessage;
        int frameLength = targetMessageByteBuf.readableBytes();
        while (targetMessageByteBuf.isReadable()) {
//            tcpIoLoop.getAckSemaphore().acquire();
            int length = tcpIoLoop.getMss();
            if (targetMessageByteBuf.readableBytes() < length) {
                length = targetMessageByteBuf.readableBytes();
            }
            byte[] ackData = ByteBufUtil.getBytes(targetMessageByteBuf.readBytes(length));
            TcpIoLoopOutputWriter.INSTANCE.writePshAckForTcpIoLoop(tcpIoLoop, ackData);
        }
        ReferenceCountUtil.release(targetMessage);
        if(frameLength< 1024){
            tcpIoLoop.switchStatus(TcpIoLoopStatus.FIN_WAITE1);
            tcpIoLoop.setVpnToAppSequenceNumber(tcpIoLoop.getVpnToAppSequenceNumber() + 1);
            Log.d(TcpIoLoopTargetToVpnHandler.class.getName(),
                    "Close tcp loop as no more data, tcp loop = " + tcpIoLoop);
            TcpIoLoopOutputWriter.INSTANCE.writeFinForTcpIoLoop(tcpIoLoop);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext targetChannelContext, Throwable cause) throws Exception {
        Channel targetChannel = targetChannelContext.channel();
        final TcpIoLoop tcpIoLoop = targetChannel.attr(IIoConstant.TCP_LOOP).get();
        Log.e(TcpIoLoopTargetToVpnHandler.class.getName(), "Exception happen on tcp loop, tcp loop =  " + tcpIoLoop,
                cause);
    }
}
