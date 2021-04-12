package com.ppaass.agent.android.io.process.udp;

import android.net.VpnService;
import android.util.Log;
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

public class UdpIoLoopFlowProcessor {
    private final Bootstrap remoteBootstrap;
    private final OutputStream remoteToDeviceStream;
    private final byte[] agentPrivateKeyBytes;
    private final byte[] proxyPublicKeyBytes;
    private final Channel remoteUdpChannel;

    public UdpIoLoopFlowProcessor(VpnService vpnService, OutputStream remoteToDeviceStream, byte[] agentPrivateKeyBytes,
                                  byte[] proxyPublicKeyBytes) {
        this.agentPrivateKeyBytes = agentPrivateKeyBytes;
        this.proxyPublicKeyBytes = proxyPublicKeyBytes;
        this.remoteBootstrap = this.createRemoteUdpChannel(vpnService, remoteToDeviceStream);
        this.remoteToDeviceStream = remoteToDeviceStream;
        try {
            this.remoteUdpChannel = this.remoteBootstrap.connect("45.63.92.64", 80).sync().channel();
        } catch (InterruptedException e) {
            throw new RuntimeException();
        }
    }

    public void shutdown() {
        this.remoteBootstrap.config().group().shutdownGracefully();
    }

    private Bootstrap createRemoteUdpChannel(VpnService vpnService, OutputStream remoteToDeviceStream) {
        System.setProperty("io.netty.selectorAutoRebuildThreshold", Integer.toString(Integer.MAX_VALUE));
        Bootstrap remoteBootstrap = new Bootstrap();
        remoteBootstrap.group(new NioEventLoopGroup(4));
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
        UdpMessageContent udpMessageContent=new UdpMessageContent();
        udpMessageContent.setData(inputUdpPacket.getData());

        AgentMessageBody agentMessageBody =
                new AgentMessageBody(
                        MessageSerializer.INSTANCE.generateUuid(),
                        "DEFAULT_USER_TOKEN",
                        destinationAddress.getHostAddress(),
                        inputUdpHeader.getDestinationPort(),
                        AgentMessageBodyType.UDP_DATA,
                       MessageSerializer.JSON_OBJECT_MAPPER.writeValueAsBytes(udpMessageContent));
        AgentMessage agentMessage =
                new AgentMessage(
                        MessageSerializer.INSTANCE.generateUuidInBytes(),
                        EncryptionType.choose(),
                        agentMessageBody);
        try {
            this.remoteUdpChannel.writeAndFlush(agentMessage);
        } catch (Exception e) {
            Log.e(UdpIoLoopFlowProcessor.class.getName(),
                    "Exception happen when execute ip packet, ip packet = " + inputIpPacket, e);
            throw new RuntimeException(e);
        }
    }
}
