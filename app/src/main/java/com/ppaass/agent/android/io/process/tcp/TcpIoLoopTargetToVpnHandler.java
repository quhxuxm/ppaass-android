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
        TcpIoLoopVpntoAppData outputData = new TcpIoLoopVpntoAppData();
        outputData.setCommand(TcpIoLoopVpnToAppCommand.DO_ACK);
        outputData.setData(ByteBufUtil.getBytes((ByteBuf) targetMessage));
//            tcpIoLoop.setVpnToAppSequenceNumber(
//                    tcpIoLoop.getAppToVpnAcknowledgementNumber() + proxyMessage.getBody().getData().length + 1);
        Log.d(TcpIoLoopTargetToVpnHandler.class.getName(),
                "Receive target data, tcp loop = " + tcpIoLoop + ", tcp output data command = " +
                        outputData.getCommand());
        Log.d(TcpIoLoopTargetToVpnHandler.class.getName(),
                "Target data: \n" + ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(outputData.getData())) + "\n");
        tcpIoLoop.offerOutputData(outputData);
        ReferenceCountUtil.release(targetMessage);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext targetChannelContext, Throwable cause) throws Exception {
        Log.e(TcpIoLoopTargetToVpnHandler.class.getName(), "Exception happen: ", cause);
    }
}
