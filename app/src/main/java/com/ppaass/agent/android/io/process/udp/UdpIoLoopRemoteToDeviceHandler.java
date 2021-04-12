package com.ppaass.agent.android.io.process.udp;

import com.ppaass.agent.android.io.process.tcp.ITcpIoLoopConstant;
import com.ppaass.common.message.ProxyMessage;
import com.ppaass.common.message.ProxyMessageBodyType;
import com.ppaass.protocol.base.ip.IpPacket;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.io.OutputStream;

@ChannelHandler.Sharable
public class UdpIoLoopRemoteToDeviceHandler extends SimpleChannelInboundHandler<ProxyMessage> {
    public UdpIoLoopRemoteToDeviceHandler() {
    }

    @Override
    public void channelRead0(ChannelHandlerContext proxyChannelContext, ProxyMessage proxyMessage)
            throws Exception {
        Channel proxyChannel = proxyChannelContext.channel();
        final OutputStream remoteToDeviceStream = proxyChannel.attr(ITcpIoLoopConstant.REMOTE_TO_DEVICE_STREAM).get();
        ProxyMessageBodyType proxyMessageBodyType = proxyMessage.getBody().getBodyType();
        if (ProxyMessageBodyType.OK_UDP == proxyMessageBodyType) {
            IpPacket udpPacketWriteToDevice = UdpIoLoopRemoteToDeviceWriter.INSTANCE
                    .buildUdpPacket(null, -1, null, -1,
                            proxyMessage.getBody().getData());
            UdpIoLoopRemoteToDeviceWriter.INSTANCE
                    .writeIpPacketToDevice(udpPacketWriteToDevice,
                            remoteToDeviceStream);
            return;
        }
        throw new RuntimeException("Can not handle proxy message body type: " + proxyMessageBodyType);
    }
}
