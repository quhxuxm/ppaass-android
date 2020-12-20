package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import com.ppaass.agent.android.io.process.IIoConstant;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;

@ChannelHandler.Sharable
public class TcpIoLoopTargetToVpnHandler extends ChannelDuplexHandler {
    public TcpIoLoopTargetToVpnHandler() {
    }

    @Override
    public void close(ChannelHandlerContext targetChannelContext, ChannelPromise promise) throws Exception {
        Channel targetChannel = targetChannelContext.channel();
        final TcpIoLoop tcpIoLoop = targetChannel.attr(IIoConstant.TCP_LOOP).get();
        tcpIoLoop.switchStatus(TcpIoLoopStatus.FIN_WAITE1);
        tcpIoLoop.setVpnToAppSequenceNumber(tcpIoLoop.getVpnToAppSequenceNumber() + 1);
        tcpIoLoop.writeToApp(tcpIoLoop.buildFin(null));
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext targetChannelContext) throws Exception {
        Channel targetChannel = targetChannelContext.channel();
        final TcpIoLoop tcpIoLoop = targetChannel.attr(IIoConstant.TCP_LOOP).get();
        tcpIoLoop.switchStatus(TcpIoLoopStatus.FIN_WAITE1);
        tcpIoLoop.setVpnToAppSequenceNumber(tcpIoLoop.getVpnToAppSequenceNumber() + 1);
        tcpIoLoop.writeToApp(tcpIoLoop.buildFin(null));
    }

    @Override
    public void channelRead(ChannelHandlerContext targetChannelContext, Object targetMessage)
            throws Exception {
        Channel targetChannel = targetChannelContext.channel();
        final TcpIoLoop tcpIoLoop = targetChannel.attr(IIoConstant.TCP_LOOP).get();
        ByteBuf targetMessageByteBuf = (ByteBuf) targetMessage;
        while (targetMessageByteBuf.isReadable()) {
            tcpIoLoop.getWriteTargetDataSemaphore().acquire();
            int length = tcpIoLoop.getMss();
            if (targetMessageByteBuf.readableBytes() < length) {
                length = targetMessageByteBuf.readableBytes();
            }
            byte[] ackData = ByteBufUtil.getBytes(targetMessageByteBuf.readBytes(length));
            tcpIoLoop.setVpnToAppSequenceNumber(tcpIoLoop.getVpnToAppSequenceNumber() + length);
            Log.d(TcpIoLoopTargetToVpnHandler.class.getName(),
                    "DO ACK[TARGET DATA], receive target data, tcp loop = " + tcpIoLoop);
            Log.d(TcpIoLoopTargetToVpnHandler.class.getName(), "TARGET DATA:\n" +
                    ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(ackData)));
            tcpIoLoop.writeToApp(tcpIoLoop.buildPshAck(ackData));
        }
        ReferenceCountUtil.release(targetMessage);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext targetChannelContext, Throwable cause) throws Exception {
        Channel targetChannel = targetChannelContext.channel();
        final TcpIoLoop tcpIoLoop = targetChannel.attr(IIoConstant.TCP_LOOP).get();
        Log.e(TcpIoLoopTargetToVpnHandler.class.getName(), "Exception happen on tcp loop, tcp loop =  " + tcpIoLoop,
                cause);
    }
}
