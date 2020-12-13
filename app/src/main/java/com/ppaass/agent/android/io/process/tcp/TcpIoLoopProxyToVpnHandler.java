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
        ProxyMessageBodyType proxyMessageBodyType = proxyMessage.getBody().getBodyType();
        if (proxyMessageBodyType == ProxyMessageBodyType.CONNECT_FAIL) {
            return;
        }
        if (proxyMessageBodyType == ProxyMessageBodyType.HEARTBEAT) {
            return;
        }
        if (proxyMessageBodyType == ProxyMessageBodyType.CONNECT_SUCCESS) {
            TcpIoLoop tcpIoLoop = proxyChannel.attr(IIoConstant.TCP_LOOP).get();
            tcpIoLoop.switchStatus(TcpIoLoopStatus.SYN_RECEIVED);
            TcpIoLoopVpntoAppData outputData = new TcpIoLoopVpntoAppData();
            outputData.setCommand(TcpIoLoopVpnToAppCommand.SYN_ACK);
            Log.i(TcpIoLoopProxyToVpnHandler.class.getName(),
                    "Receive proxy connect success response, tcp loop key = " + tcpIoLoop.getKey() +
                            ", tcp loop status = " +
                            tcpIoLoop.getStatus() + ", command sent to app = " + outputData.getCommand() +
                            ", data = \n" + new String(proxyMessage.getBody().getData()) + "\n");
            tcpIoLoop.offerOutputData(outputData);
            return;
        }
        if (proxyMessageBodyType == ProxyMessageBodyType.OK_TCP) {
            TcpIoLoop tcpIoLoop = proxyChannel.attr(IIoConstant.TCP_LOOP).get();
            TcpIoLoopVpntoAppData outputData = new TcpIoLoopVpntoAppData();
            outputData.setCommand(TcpIoLoopVpnToAppCommand.ACK);
            outputData.setData(proxyMessage.getBody().getData());
            Log.i(TcpIoLoopProxyToVpnHandler.class.getName(),
                    "Receive proxy data, tcp loop key = " + tcpIoLoop.getKey() + ", tcp loop status = " +
                            tcpIoLoop.getStatus() + ", command sent to app = " + outputData.getCommand() +
                            ", data = \n" + new String(outputData.getData()) + "\n");
            tcpIoLoop.offerOutputData(outputData);
            return;
        }
        Log.e(TcpIoLoopProxyToVpnHandler.class.getName(), "Do not support other message body type.");
    }
}
