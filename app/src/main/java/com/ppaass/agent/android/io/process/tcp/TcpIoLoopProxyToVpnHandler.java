package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import com.ppaass.agent.android.io.process.IIoConstant;
import com.ppaass.kt.common.Message;
import com.ppaass.kt.common.ProxyMessageBodyType;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

@ChannelHandler.Sharable
public class TcpIoLoopProxyToVpnHandler extends SimpleChannelInboundHandler<Message<ProxyMessageBodyType>> {
    public TcpIoLoopProxyToVpnHandler() {
    }

    @Override
    protected void channelRead0(ChannelHandlerContext proxyChannelContext, Message<ProxyMessageBodyType> proxyMessage)
            throws Exception {
        Channel proxyChannel = proxyChannelContext.channel();
        TcpIoLoop tcpIoLoop = proxyChannel.attr(IIoConstant.TCP_LOOP).get();
        ProxyMessageBodyType proxyMessageBodyType = proxyMessage.getBody().getBodyType();
        if (proxyMessageBodyType == ProxyMessageBodyType.CONNECT_FAIL) {
            return;
        }
        if (proxyMessageBodyType == ProxyMessageBodyType.HEARTBEAT) {
            return;
        }
        if (proxyMessageBodyType == ProxyMessageBodyType.CONNECT_SUCCESS) {
            tcpIoLoop.switchStatus(TcpIoLoopStatus.SYN_RECEIVED);
            TcpIoLoopVpntoAppData outputData = new TcpIoLoopVpntoAppData();
            outputData.setCommand(TcpIoLoopVpnToAppCommand.SYN_ACK);
            Log.d(TcpIoLoopProxyToVpnHandler.class.getName(),
                    "Receive proxy connect success response, tcp loop = " + tcpIoLoop + ", tcp output data = " +
                            outputData);
            tcpIoLoop.offerOutputData(outputData);
            return;
        }
        if (proxyMessageBodyType == ProxyMessageBodyType.OK_TCP) {
            TcpIoLoopVpntoAppData outputData = new TcpIoLoopVpntoAppData();
            outputData.setCommand(TcpIoLoopVpnToAppCommand.ACK);
            outputData.setData(proxyMessage.getBody().getData());
            Log.d(TcpIoLoopProxyToVpnHandler.class.getName(),
                    "Receive proxy connect success response, tcp loop = " + tcpIoLoop + ", tcp output data = " +
                            outputData);
            tcpIoLoop.offerOutputData(outputData);
            return;
        }
        Log.e(TcpIoLoopProxyToVpnHandler.class.getName(),
                "Do not support other message body type, tcp loop = " + tcpIoLoop);
    }
}
