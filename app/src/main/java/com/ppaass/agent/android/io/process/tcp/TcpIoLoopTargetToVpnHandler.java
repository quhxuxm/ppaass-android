package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import com.ppaass.agent.android.io.process.IIoConstant;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

@ChannelHandler.Sharable
public class TcpIoLoopTargetToVpnHandler extends ChannelInboundHandlerAdapter {
    public TcpIoLoopTargetToVpnHandler() {
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
            if (tcpIoLoop.getWindow() < length) {
                length = tcpIoLoop.getWindow();
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
