package com.ppaass.agent.android.io.process.tcp;

import android.net.VpnService;
import android.util.Log;
import com.ppaass.agent.android.io.process.common.VpnNioSocketChannel;
import com.ppaass.agent.android.io.protocol.ip.IpPacket;
import com.ppaass.agent.android.io.protocol.ip.IpV4Header;
import com.ppaass.agent.android.io.protocol.tcp.TcpPacket;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.PreferHeapByteBufAllocator;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import org.jetbrains.annotations.Nullable;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.ppaass.agent.android.io.process.tcp.ITcpIoLoopConstant.TCP_IO_LOOP_KEY_FORMAT;

public class TcpIoLoopProcessor {
    private static final int BASE_TCP_LOOP_SEQUENCE = (int) (Math.random() * 100000);
    private final VpnService vpnService;
    private final byte[] agentPrivateKeyBytes;
    private final byte[] proxyPublicKeyBytes;
    private final Bootstrap remoteBootstrap;
    private final ConcurrentMap<String, TcpIoLoop> tcpIoLoops;
    private final OutputStream remoteToDeviceStream;
    private final ExecutorService loopExecutor;
    private int createTcpLoopCounter;

    public TcpIoLoopProcessor(VpnService vpnService, byte[] agentPrivateKeyBytes, byte[] proxyPublicKeyBytes,
                              OutputStream remoteToDeviceStream) {
        this.vpnService = vpnService;
        this.agentPrivateKeyBytes = agentPrivateKeyBytes;
        this.proxyPublicKeyBytes = proxyPublicKeyBytes;
        this.remoteToDeviceStream = remoteToDeviceStream;
        this.remoteBootstrap = this.createRemoteBootstrap();
        this.tcpIoLoops = new ConcurrentHashMap<>();
        this.loopExecutor = Executors.newFixedThreadPool(20);
        this.createTcpLoopCounter = 0;
    }

    public void process(IpPacket ipPacket) {
        TcpIoLoop tcpIoLoop = computeIoLoop(ipPacket);
        if (tcpIoLoop == null) {
            Log.e(TcpIoLoopProcessor.class.getName(),
                    "Some problem happen can not process ip packet, ip packet = " + ipPacket);
            return;
        }
        Log.v(TcpIoLoopProcessor.class.getName(),
                "Put ip packet to tcp loop, tcp loop = " + tcpIoLoop.getLoopInfo() + ", current ip packet = " +
                        ipPacket + ", queued packets = " + tcpIoLoop.getDeviceToRemoteIpPacketQueue());
        tcpIoLoop.offerIpPacket(ipPacket);
    }

    @Nullable
    private TcpIoLoop computeIoLoop(IpPacket ipPacket) {
        IpV4Header ipV4Header = (IpV4Header) ipPacket.getHeader();
        TcpPacket tcpPacket = (TcpPacket) ipPacket.getData();
        final InetAddress sourceAddress;
        try {
            sourceAddress = InetAddress.getByAddress(ipV4Header.getSourceAddress());
        } catch (UnknownHostException e) {
            Log.e(TcpIoLoopProcessor.class.getName(),
                    "Fail to process ip packet because of source address is a unknown host, ip packet = " + ipPacket,
                    e);
            return null;
        }
        final int sourcePort = tcpPacket.getHeader().getSourcePort();
        final InetAddress destinationAddress;
        try {
            destinationAddress = InetAddress.getByAddress(ipV4Header.getDestinationAddress());
        } catch (UnknownHostException e) {
            Log.e(TcpIoLoopProcessor.class.getName(),
                    "Fail to process ip packet because of destination address is a unknown host, ip packet = " +
                            ipPacket,
                    e);
            return null;
        }
        final int destinationPort = tcpPacket.getHeader().getDestinationPort();
        final String tcpIoLoopKey = this.generateLoopKey(sourceAddress, sourcePort
                , destinationAddress, destinationPort
        );
        return this.tcpIoLoops.computeIfAbsent(tcpIoLoopKey,
                (key) -> {
                    this.createTcpLoopCounter++;
                    TcpIoLoopInfo loopInfo =
                            new TcpIoLoopInfo(key,
                                    BASE_TCP_LOOP_SEQUENCE + this.createTcpLoopCounter,
                                    sourceAddress,
                                    destinationAddress,
                                    sourcePort,
                                    destinationPort);
                    loopInfo.setStatus(TcpIoLoopStatus.LISTEN);
                    TcpIoLoop loop = new TcpIoLoop(loopInfo, remoteBootstrap, remoteToDeviceStream, this.tcpIoLoops);
                    this.loopExecutor.execute(loop);
                    Log.d(TcpIoLoopProcessor.class.getName(),
                            "Create tcp loop, ip packet = " + ipPacket + ", tcp loop = " + loopInfo +
                                    ", loop container size = " + tcpIoLoops.size());
                    return loop;
                });
    }

    private String generateLoopKey(InetAddress sourceAddress, int sourcePort, InetAddress destinationAddress,
                                   int destinationPort) {
        return String.format(TCP_IO_LOOP_KEY_FORMAT, sourceAddress.getHostAddress(), sourcePort,
                destinationAddress.getHostAddress(), destinationPort);
    }

    private Bootstrap createRemoteBootstrap() {
        Bootstrap remoteBootstrap = new Bootstrap();
        remoteBootstrap.group(new NioEventLoopGroup(40));
        remoteBootstrap.channelFactory(() -> new VpnNioSocketChannel(this.vpnService));
        remoteBootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000);
        remoteBootstrap.option(ChannelOption.SO_KEEPALIVE, false);
        remoteBootstrap.option(ChannelOption.AUTO_READ, true);
        remoteBootstrap.option(ChannelOption.AUTO_CLOSE, false);
        remoteBootstrap.option(ChannelOption.ALLOCATOR, PreferHeapByteBufAllocator.DEFAULT);
        remoteBootstrap.option(ChannelOption.TCP_NODELAY, true);
        remoteBootstrap.option(ChannelOption.SO_REUSEADDR, true);
        remoteBootstrap.option(ChannelOption.SO_LINGER, -1);
        remoteBootstrap.option(ChannelOption.SO_RCVBUF, 65536);
        remoteBootstrap.option(ChannelOption.SO_SNDBUF, 65536);
        remoteBootstrap.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel remoteChannel) {
                ChannelPipeline remoteChannelPipeline = remoteChannel.pipeline();
                remoteChannelPipeline.addLast(new TcpIoLoopRemoteToDeviceHandler());
            }
        });
        remoteBootstrap.attr(ITcpIoLoopConstant.REMOTE_TO_DEVICE_STREAM, this.remoteToDeviceStream);
        return remoteBootstrap;
    }
}
