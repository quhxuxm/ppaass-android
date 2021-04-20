package com.ppaass.agent.android.io.process.udp;

import android.util.Log;
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
    private final OutputStream remoteToDeviceStream;

    public UdpIoLoopRemoteToDeviceHandler(OutputStream remoteToDeviceStream) {
        this.remoteToDeviceStream = remoteToDeviceStream;
    }

    @Override
    public void channelRead0(ChannelHandlerContext proxyChannelContext, ProxyMessage proxyMessage)
            throws Exception {
        Channel proxyChannel = proxyChannelContext.channel();
        ProxyMessageBodyType proxyMessageBodyType = proxyMessage.getBody().getBodyType();
        if (ProxyMessageBodyType.OK_UDP != proxyMessageBodyType) {
            Log.i(UdpIoLoopRemoteToDeviceHandler.class.getName(),
                    "Fail to receive udp message.");
            return;
        }
        UdpTransferMessageContent udpTransferMessageContent = MessageSerializer.JSON_OBJECT_MAPPER
                .readValue(proxyMessage.getBody().getData(), UdpTransferMessageContent.class);
        byte[] originalDestinationAddressBytes =
                InetAddress.getByName(udpTransferMessageContent.getOriginalDestinationAddress()).getAddress();
        byte[] originalSourceAddressBytes =
                InetAddress.getByName(udpTransferMessageContent.getOriginalSourceAddress()).getAddress();
        IpPacket udpPacketWriteToDevice = UdpIoLoopRemoteToDeviceWriter.INSTANCE
                .buildUdpPacket(originalDestinationAddressBytes, udpTransferMessageContent.getOriginalDestinationPort(),
                        originalSourceAddressBytes, udpTransferMessageContent.getOriginalSourcePort(),
                        udpTransferMessageContent.getData());
        UdpIoLoopRemoteToDeviceWriter.INSTANCE
                .writeIpPacketToDevice(udpPacketWriteToDevice,
                        remoteToDeviceStream);
        Log.i(UdpIoLoopRemoteToDeviceHandler.class.getName(),
                "Success receive udp message, source address = " +
                        udpTransferMessageContent.getOriginalSourceAddress() +
                        ", source port =" + udpTransferMessageContent.getOriginalSourcePort() +
                        ", destination address =" + udpTransferMessageContent.getOriginalDestinationAddress() +
                        ", destination port = " + udpTransferMessageContent.getOriginalDestinationPort() +
                        ", content:\n" +
                        ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(udpTransferMessageContent.getData())) +
                        "\n");
    }
}
