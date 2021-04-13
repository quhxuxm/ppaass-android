package com.ppaass.agent.android.io.process.udp;

import android.util.Log;
import com.ppaass.agent.android.io.process.tcp.ITcpIoLoopConstant;
import com.ppaass.common.message.MessageSerializer;
import com.ppaass.common.message.ProxyMessage;
import com.ppaass.common.message.ProxyMessageBodyType;
import com.ppaass.common.message.UdpTransferMessageContent;
import com.ppaass.protocol.base.ip.IpPacket;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.io.OutputStream;
import java.net.InetAddress;

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
            UdpTransferMessageContent udpTransferMessageContent = MessageSerializer.JSON_OBJECT_MAPPER
                    .readValue(proxyMessage.getBody().getData(), UdpTransferMessageContent.class);
            byte[] originalDestinationAddressBytes =
                    InetAddress.getByName(udpTransferMessageContent.getOriginalDestinationAddress()).getAddress();
            byte[] originalSourceAddressBytes =
                    InetAddress.getByName(udpTransferMessageContent.getOriginalSourceAddress()).getAddress();
            IpPacket udpPacketWriteToDevice = UdpIoLoopRemoteToDeviceWriter.INSTANCE
                    .buildUdpPacket(originalDestinationAddressBytes, udpTransferMessageContent.getOriginalSourcePort(),
                            originalSourceAddressBytes, udpTransferMessageContent.getOriginalSourcePort(),
                            udpTransferMessageContent.getData());
            UdpIoLoopRemoteToDeviceWriter.INSTANCE
                    .writeIpPacketToDevice(udpPacketWriteToDevice,
                            remoteToDeviceStream);
            Log.i(UdpIoLoopRemoteToDeviceHandler.class.getName(),
                    "Success receive udp message, source address = " + udpTransferMessageContent.getOriginalSourceAddress() +
                            ", source port =" + udpTransferMessageContent.getOriginalSourcePort() +
                            ", destination address =" + udpTransferMessageContent.getOriginalDestinationAddress() +
                            ", destination port = " + udpTransferMessageContent.getOriginalDestinationPort() +
                            ", content:\n" +
                            ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(udpTransferMessageContent.getData())) +
                            "\n");
            return;
        }
        throw new RuntimeException("Can not handle proxy message body type: " + proxyMessageBodyType);
    }
}
