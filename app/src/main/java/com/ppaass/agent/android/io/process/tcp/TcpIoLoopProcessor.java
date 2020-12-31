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
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import org.jetbrains.annotations.Nullable;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.*;

import static com.ppaass.agent.android.io.process.tcp.ITcpIoLoopConstant.TCP_IO_LOOP_KEY_FORMAT;

public class TcpIoLoopProcessor {
    private final VpnService vpnService;
    private final byte[] agentPrivateKeyBytes;
    private final byte[] proxyPublicKeyBytes;
    private final ConcurrentMap<String, TcpIoLoop> tcpIoLoops;
    private final OutputStream remoteToDeviceStream;
    private final NioEventLoopGroup remoteNioEventLoopGroup;
    private final ScheduledExecutorService twoMslTimerExecutor;
    private final ExecutorService tcpIoLoopFlowTaskExecutor;
    private final ExecutorService clearExecutor;
    private final Bootstrap remoteBootstrap;
    private boolean alive;

    public TcpIoLoopProcessor(VpnService vpnService, byte[] agentPrivateKeyBytes, byte[] proxyPublicKeyBytes,
                              OutputStream remoteToDeviceStream) {
        this.vpnService = vpnService;
        this.agentPrivateKeyBytes = agentPrivateKeyBytes;
        this.proxyPublicKeyBytes = proxyPublicKeyBytes;
        this.remoteToDeviceStream = remoteToDeviceStream;
        this.tcpIoLoops = new ConcurrentHashMap<>();
        this.remoteNioEventLoopGroup = new NioEventLoopGroup(32);
        this.twoMslTimerExecutor = Executors.newScheduledThreadPool(32);
        this.tcpIoLoopFlowTaskExecutor = Executors.newFixedThreadPool(32);
        this.remoteBootstrap = this.createRemoteBootstrap();
        this.clearExecutor = Executors.newSingleThreadExecutor();
        this.alive = true;
        this.clearExecutor.execute(() -> {
            while (this.alive) {
                tcpIoLoops.forEach((key, tcpIoLoop) -> {
                    if (System.currentTimeMillis() - tcpIoLoop.getUpdateTime() > 20000) {
                        tcpIoLoop.destroy();
                    }
                });
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    Log.e(TcpIoLoopProcessor.class.getName(), "Clear thread get a error", e);
                }
                Log.d(TcpIoLoopProcessor.class.getName(),
                        "Tcp loop in memory: " + tcpIoLoops);
            }
        });
    }

    public void shutdown() {
        this.remoteNioEventLoopGroup.shutdownGracefully();
        this.twoMslTimerExecutor.shutdown();
        this.tcpIoLoopFlowTaskExecutor.shutdown();
        tcpIoLoops.clear();
        this.alive = false;
    }

    public void process(IpPacket ipPacket) {
        TcpIoLoop tcpIoLoop = computeIoLoop(ipPacket);
        if (tcpIoLoop == null) {
            Log.e(TcpIoLoopProcessor.class.getName(),
                    "Some problem happen can not process ip packet, ip packet = " + ipPacket);
            return;
        }
        tcpIoLoop.getDeviceInputQueue().offer(ipPacket);
        tcpIoLoop.setUpdateTime(System.currentTimeMillis());
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
                    TcpIoLoop tcpIoLoop =
                            new TcpIoLoop(key, System.currentTimeMillis(),
                                    sourceAddress,
                                    destinationAddress,
                                    sourcePort,
                                    destinationPort, this.tcpIoLoops, remoteToDeviceStream);
                    tcpIoLoop.setStatus(TcpIoLoopStatus.LISTEN);
                    TcpIoLoopFlowTask
                            flowTask =
                            new TcpIoLoopFlowTask(tcpIoLoop, this.remoteBootstrap, twoMslTimerExecutor);
                    tcpIoLoop.setFlowTask(flowTask);
                    Log.d(TcpIoLoopProcessor.class.getName(),
                            "Create tcp loop, ip packet = " + ipPacket + ", tcp loop = " + tcpIoLoop +
                                    ", loop container size = " + tcpIoLoops.size());
                    flowTask.start();
                    this.tcpIoLoopFlowTaskExecutor.execute(tcpIoLoop.getFlowTask());
                    return tcpIoLoop;
                });
    }

    private String generateLoopKey(InetAddress sourceAddress, int sourcePort, InetAddress destinationAddress,
                                   int destinationPort) {
        return String.format(TCP_IO_LOOP_KEY_FORMAT, sourceAddress.getHostAddress(), sourcePort,
                destinationAddress.getHostAddress(), destinationPort);
    }

    private Bootstrap createRemoteBootstrap() {
        Bootstrap remoteBootstrap = new Bootstrap();
        remoteBootstrap.group(remoteNioEventLoopGroup);
        remoteBootstrap.channelFactory(() -> new VpnNioSocketChannel(this.vpnService));
        remoteBootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000);
        remoteBootstrap.option(ChannelOption.SO_KEEPALIVE, true);
        remoteBootstrap.option(ChannelOption.AUTO_READ, true);
        remoteBootstrap.option(ChannelOption.AUTO_CLOSE, true);
        remoteBootstrap.option(ChannelOption.TCP_NODELAY, true);
        remoteBootstrap.option(ChannelOption.SO_REUSEADDR, true);
        remoteBootstrap.option(ChannelOption.SO_LINGER, 1);
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
