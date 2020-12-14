package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import com.ppaass.agent.android.io.process.IIoConstant;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

@ChannelHandler.Sharable
public class TcpIoLoopTargetToVpnHandler extends SimpleChannelInboundHandler<ByteBuf> {
    public TcpIoLoopTargetToVpnHandler() {
    }

    @Override
    protected void channelRead0(ChannelHandlerContext targetChannelContext, ByteBuf targetMessage)
            throws Exception {
        Channel targetChannel = targetChannelContext.channel();
        final TcpIoLoop tcpIoLoop = targetChannel.attr(IIoConstant.TCP_LOOP).get();
        TcpIoLoopVpntoAppData outputData = new TcpIoLoopVpntoAppData();
        outputData.setCommand(TcpIoLoopVpnToAppCommand.DO_ACK);
        outputData.setData(ByteBufUtil.getBytes(targetMessage));
//            tcpIoLoop.setVpnToAppSequenceNumber(
//                    tcpIoLoop.getAppToVpnAcknowledgementNumber() + proxyMessage.getBody().getData().length + 1);
        Log.d(TcpIoLoopTargetToVpnHandler.class.getName(),
                "Receive target data, tcp loop = " + tcpIoLoop + ", tcp output data = " +
                        outputData);
        tcpIoLoop.offerOutputData(outputData);
    }
}
