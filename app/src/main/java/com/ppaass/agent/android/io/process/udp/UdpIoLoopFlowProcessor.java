package com.ppaass.agent.android.io.process.udp;

import android.net.VpnService;
import android.util.Log;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.ppaass.agent.android.io.process.common.VpnNioSocketChannel;
import com.ppaass.agent.android.io.process.tcp.ITcpIoLoopConstant;
import com.ppaass.common.cryptography.EncryptionType;
import com.ppaass.common.handler.AgentMessageEncoder;
import com.ppaass.common.handler.PrintExceptionHandler;
import com.ppaass.common.handler.ProxyMessageDecoder;
import com.ppaass.common.message.*;
import com.ppaass.protocol.base.ip.IpPacket;
import com.ppaass.protocol.base.ip.IpV4Header;
import com.ppaass.protocol.base.udp.UdpHeader;
import com.ppaass.protocol.base.udp.UdpPacket;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.compression.Lz4FrameDecoder;
import io.netty.handler.codec.compression.Lz4FrameEncoder;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class UdpIoLoopFlowProcessor {
    private static final int POOL_SIZE = 32;
    private final Bootstrap remoteBootstrap;
    private final byte[] agentPrivateKeyBytes;
    private final byte[] proxyPublicKeyBytes;
    private List<Channel> udpChannelsPool;
    private Random random;

    public UdpIoLoopFlowProcessor(VpnService vpnService, OutputStream remoteToDeviceStream, byte[] agentPrivateKeyBytes,
                                  byte[] proxyPublicKeyBytes) {
        this.agentPrivateKeyBytes = agentPrivateKeyBytes;
        this.proxyPublicKeyBytes = proxyPublicKeyBytes;
        this.remoteBootstrap = this.createRemoteUdpChannel(vpnService, remoteToDeviceStream);
        this.udpChannelsPool = new ArrayList<>();
        for (int i = 0; i < POOL_SIZE; i++) {
            try {
                this.udpChannelsPool.add(this.remoteBootstrap.connect("45.63.92.64", 80).sync().channel());
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        this.random = new Random();
    }

    public void shutdown() {
        this.remoteBootstrap.config().group().shutdownGracefully();
    }

    private Bootstrap createRemoteUdpChannel(VpnService vpnService, OutputStream remoteToDeviceStream) {
        System.setProperty("io.netty.selectorAutoRebuildThreshold", Integer.toString(Integer.MAX_VALUE));
        Bootstrap remoteBootstrap = new Bootstrap();
        remoteBootstrap.group(new NioEventLoopGroup(POOL_SIZE));
        remoteBootstrap.channelFactory(() -> new VpnNioSocketChannel(vpnService));
        remoteBootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000);
        remoteBootstrap.option(ChannelOption.SO_KEEPALIVE, true);
        remoteBootstrap.option(ChannelOption.AUTO_READ, true);
        remoteBootstrap.option(ChannelOption.AUTO_CLOSE, false);
        remoteBootstrap.option(ChannelOption.TCP_NODELAY, true);
        remoteBootstrap.option(ChannelOption.SO_REUSEADDR, true);
        remoteBootstrap.option(ChannelOption.SO_LINGER, 1);
        remoteBootstrap.option(ChannelOption.SO_RCVBUF, 131072);
        remoteBootstrap.option(ChannelOption.SO_SNDBUF, 131072);
        remoteBootstrap.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel remoteChannel) {
                ChannelPipeline proxyChannelPipeline = remoteChannel.pipeline();
                proxyChannelPipeline.addLast(new Lz4FrameDecoder());
                proxyChannelPipeline.addLast(new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 4, 0, 4));
                proxyChannelPipeline.addLast(new ProxyMessageDecoder(UdpIoLoopFlowProcessor.this.agentPrivateKeyBytes));
                proxyChannelPipeline.addLast(new UdpIoLoopRemoteToDeviceHandler());
                proxyChannelPipeline.addLast(new Lz4FrameEncoder());
                proxyChannelPipeline.addLast(new LengthFieldPrepender(4));
                proxyChannelPipeline.addLast(new AgentMessageEncoder(UdpIoLoopFlowProcessor.this.proxyPublicKeyBytes));
                proxyChannelPipeline.addLast(new PrintExceptionHandler());
            }
        });
        remoteBootstrap.attr(ITcpIoLoopConstant.REMOTE_TO_DEVICE_STREAM, remoteToDeviceStream);
        return remoteBootstrap;
    }

    public void execute(IpPacket inputIpPacket) {
        IpV4Header inputIpV4Header = (IpV4Header) inputIpPacket.getHeader();
        UdpPacket inputUdpPacket = (UdpPacket) inputIpPacket.getData();
        UdpHeader inputUdpHeader = inputUdpPacket.getHeader();
        final InetAddress destinationAddress;
        try {
            destinationAddress = InetAddress.getByAddress(inputIpV4Header.getDestinationAddress());
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
        final InetAddress sourceAddress;
        try {
            sourceAddress = InetAddress.getByAddress(inputIpV4Header.getSourceAddress());
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
        UdpTransferMessageContent udpTransferMessageContent = new UdpTransferMessageContent();
        udpTransferMessageContent.setData(inputUdpPacket.getData());
        udpTransferMessageContent.setOriginalDestinationAddress(destinationAddress.getHostAddress());
        udpTransferMessageContent.setOriginalDestinationPort(inputUdpHeader.getDestinationPort());
        udpTransferMessageContent.setOriginalSourceAddress(sourceAddress.getHostAddress());
        udpTransferMessageContent.setOriginalSourcePort(inputUdpHeader.getSourcePort());
        udpTransferMessageContent.setOriginalAddrType(UdpTransferMessageContent.AddrType.IPV4);
        Log.i(UdpIoLoopFlowProcessor.class.getName(),
                "Send udp request, source address = " + udpTransferMessageContent.getOriginalSourceAddress() +
                        ", source port =" + udpTransferMessageContent.getOriginalSourcePort() +
                        ", destination address =" + udpTransferMessageContent.getOriginalDestinationAddress() +
                        ", destination port = " + udpTransferMessageContent.getOriginalDestinationPort() +
                        ", content:\n" +
                        ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(udpTransferMessageContent.getData())) +
                        "\n");
        byte[] udpTransferMessageContentBytes;
        try {
            udpTransferMessageContentBytes =
                    MessageSerializer.JSON_OBJECT_MAPPER.writeValueAsBytes(udpTransferMessageContent);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        AgentMessageBody agentMessageBody =
                new AgentMessageBody(
                        MessageSerializer.INSTANCE.generateUuid(),
                        "DEFAULT_USER_TOKEN",
                        destinationAddress.getHostAddress(),
                        inputUdpHeader.getDestinationPort(),
                        AgentMessageBodyType.UDP_DATA,
                        udpTransferMessageContentBytes);
        AgentMessage agentMessage =
                new AgentMessage(
                        MessageSerializer.INSTANCE.generateUuidInBytes(),
                        EncryptionType.choose(),
                        agentMessageBody);
        Channel remoteUdpChannel =
                this.udpChannelsPool.get(Math.abs(this.random.nextInt()) % this.udpChannelsPool.size());
        remoteUdpChannel.writeAndFlush(agentMessage).syncUninterruptibly();
    }
}
