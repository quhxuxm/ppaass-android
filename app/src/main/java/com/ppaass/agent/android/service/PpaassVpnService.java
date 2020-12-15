package com.ppaass.agent.android.service;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import com.ppaass.agent.android.R;
import com.ppaass.agent.android.io.process.IIoLoop;
import com.ppaass.agent.android.io.process.IoLoopHolder;
import com.ppaass.agent.android.io.process.common.VpnNioSocketChannel;
import com.ppaass.agent.android.io.process.tcp.TcpIoLoop;
import com.ppaass.agent.android.io.process.tcp.TcpIoLoopTargetToVpnHandler;
import com.ppaass.agent.android.io.process.udp.UdpIoLoop;
import com.ppaass.agent.android.io.protocol.ip.*;
import com.ppaass.agent.android.io.protocol.tcp.TcpPacket;
import com.ppaass.agent.android.io.protocol.udp.UdpPacket;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.PreferHeapByteBufAllocator;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;

import java.io.*;
import java.net.InetAddress;

import static com.ppaass.agent.android.IPpaassConstant.VPN_ADDRESS;
import static com.ppaass.agent.android.IPpaassConstant.VPN_ROUTE;

public class PpaassVpnService extends VpnService {
    private static final int VPN_BUFFER_SIZE = 32768;
    private Bootstrap proxyTcpBootstrap;
    private Bootstrap proxyUdpBootstrap;
    private FileInputStream vpnInputStream;
    private FileOutputStream vpnOutputStream;
    private ParcelFileDescriptor vpnInterface;
    private boolean alive;

    @Override
    public void onCreate() {
        super.onCreate();
        final byte[] agentPrivateKeyBytes;
        try {
            InputStream agentPrivateKeyStream =
                    this.getResources().openRawResource(R.raw.agentprivatekey);
            agentPrivateKeyBytes = new byte[agentPrivateKeyStream.available()];
            int readAgentPrivateKeyBytesResult = agentPrivateKeyStream.read(agentPrivateKeyBytes);
            if (readAgentPrivateKeyBytesResult < 0) {
                throw new RuntimeException();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        final byte[] proxyPublicKeyBytes;
        try {
            InputStream proxyPublicKeyStream =
                    this.getResources().openRawResource(R.raw.proxypublickey);
            proxyPublicKeyBytes = new byte[proxyPublicKeyStream.available()];
            int readProxyPublicKeyBytesResult = proxyPublicKeyStream.read(proxyPublicKeyBytes);
            if (readProxyPublicKeyBytesResult < 0) {
                throw new RuntimeException();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        proxyTcpBootstrap =
                PpaassVpnService.this.createProxyBootstrap(proxyPublicKeyBytes, agentPrivateKeyBytes);
        proxyUdpBootstrap =
                PpaassVpnService.this.createProxyBootstrap(proxyPublicKeyBytes, agentPrivateKeyBytes);
        if (this.vpnInterface == null) {
            Builder vpnBuilder = new Builder();
            vpnBuilder.addAddress(VPN_ADDRESS, 32);
            vpnBuilder.addRoute(VPN_ROUTE, 0);
            this.vpnInterface =
                    vpnBuilder.setSession(getString(R.string.app_name)).establish();
            final FileDescriptor vpnFileDescriptor = vpnInterface.getFileDescriptor();
            this.vpnInputStream = new FileInputStream(vpnFileDescriptor);
            this.vpnOutputStream = new FileOutputStream(vpnFileDescriptor);
        }
        this.alive = true;
        Thread vpnThread = new Thread(() -> {
            while (alive) {
                byte[] buffer = new byte[VPN_BUFFER_SIZE];
                try {
                    int readResult = vpnInputStream.read(buffer);
                    if (readResult <= 0) {
                        Thread.sleep(100);
                        continue;
                    }
                    IpPacket ipPacket = IpPacketReader.INSTANCE.parse(buffer);
                    if (IpHeaderVersion.V4 != ipPacket.getHeader().getVersion()) {
                        continue;
                    }
                    IpV4Header ipV4Header = (IpV4Header) ipPacket.getHeader();
                    IpDataProtocol protocol = ipV4Header.getProtocol();
                    if (IpDataProtocol.TCP == protocol) {
                        TcpPacket tcpPacket = (TcpPacket) ipPacket.getData();
                        final InetAddress sourceAddress = InetAddress.getByAddress(ipV4Header.getSourceAddress());
                        final int sourcePort = tcpPacket.getHeader().getSourcePort();
                        final InetAddress destinationAddress =
                                InetAddress.getByAddress(ipV4Header.getDestinationAddress());
                        final int destinationPort = tcpPacket.getHeader().getDestinationPort();
                        final String ioLoopKey = IoLoopHolder.INSTANCE
                                .generateLoopKey(sourceAddress, sourcePort
                                        , destinationAddress, destinationPort
                                );
                        IoLoopHolder.INSTANCE.getIoLoops().computeIfAbsent(ioLoopKey,
                                (key) -> {
                                    TcpIoLoop result = new TcpIoLoop(sourceAddress, destinationAddress, sourcePort,
                                            destinationPort, key, proxyTcpBootstrap, vpnOutputStream);
                                    result.init();
                                    result.start();
                                    Log.d(PpaassVpnService.class.getName(),
                                            "Initialize tcp loop, tcp packet=" + tcpPacket + ", tcp loop = " + result);
                                    return result;
                                });
                        IIoLoop<?> ioLoop = IoLoopHolder.INSTANCE.getIoLoops().get(ioLoopKey);
                        if (ioLoop == null) {
                            throw new RuntimeException();
                        }
                        ioLoop.offerInputIpPacket(ipPacket);
                        continue;
                    }
                    if (IpDataProtocol.UDP == protocol) {
                        UdpPacket udpPacket = (UdpPacket) ipPacket.getData();
                        final InetAddress sourceAddress = InetAddress.getByAddress(ipV4Header.getSourceAddress());
                        final int sourcePort = udpPacket.getHeader().getSourcePort();
                        final InetAddress destinationAddress =
                                InetAddress.getByAddress(ipV4Header.getDestinationAddress());
                        final int destinationPort = udpPacket.getHeader().getDestinationPort();
                        final String ioLoopKey = IoLoopHolder.INSTANCE
                                .generateLoopKey(sourceAddress, sourcePort
                                        , destinationAddress, destinationPort
                                );
                        IoLoopHolder.INSTANCE.getIoLoops().computeIfAbsent(ioLoopKey,
                                (key) -> {
                                    UdpIoLoop result = new UdpIoLoop(sourceAddress, destinationAddress, sourcePort,
                                            destinationPort, key, proxyUdpBootstrap);
                                    result.init();
                                    result.start();
                                    return result;
                                });
                        IIoLoop<?> ioLoop = IoLoopHolder.INSTANCE.getIoLoops().get(ioLoopKey);
                        if (ioLoop == null) {
                            throw new RuntimeException();
                        }
                        ioLoop.offerInputIpPacket(ipPacket);
                        continue;
                    }
                    Log.e(PpaassVpnService.class.getName(), "Do not support other protocol, protocol = " + protocol);
                    throw new UnsupportedOperationException("Do not support other protocol.");
                } catch (IOException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        vpnThread.start();
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private Bootstrap createProxyBootstrap(byte[] proxyPublicKey, byte[] agentPrivateKey) {
        Bootstrap proxyBootstrap = new Bootstrap();
        proxyBootstrap.group(new NioEventLoopGroup());
        proxyBootstrap.channelFactory(() -> new VpnNioSocketChannel(PpaassVpnService.this));
        proxyBootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 20000);
        proxyBootstrap.option(ChannelOption.SO_KEEPALIVE, true);
        proxyBootstrap.option(ChannelOption.AUTO_READ, true);
        proxyBootstrap.option(ChannelOption.AUTO_CLOSE, false);
        proxyBootstrap.option(ChannelOption.ALLOCATOR, PreferHeapByteBufAllocator.DEFAULT);
        proxyBootstrap.option(ChannelOption.TCP_NODELAY, true);
        proxyBootstrap.option(ChannelOption.SO_REUSEADDR, true);
        proxyBootstrap.option(ChannelOption.SO_LINGER, -1);
        proxyBootstrap.option(ChannelOption.SO_RCVBUF, 65536);
        proxyBootstrap.option(ChannelOption.SO_SNDBUF, 65536);
        proxyBootstrap.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel proxyChannel) {
                ChannelPipeline proxyChannelPipeline = proxyChannel.pipeline();
                // addLast(Lz4FrameDecoder())
//                proxyChannelPipeline.addLast(new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 4, 0, 4));
//                proxyChannelPipeline.addLast(new ProxyMessageDecoder(agentPrivateKey));
//                proxyChannelPipeline.addLast(new TcpIoLoopProxyToVpnHandler());
                proxyChannelPipeline.addLast(new TcpIoLoopTargetToVpnHandler());
                // addLast(Lz4FrameEncoder())
//                proxyChannelPipeline.addLast(new LengthFieldPrepender(4));
//                proxyChannelPipeline.addLast(new AgentMessageEncoder(proxyPublicKey));
            }
        });
        return proxyBootstrap;
    }

    @Override
    public void onDestroy() {
        this.alive = false;
        IoLoopHolder.INSTANCE.getIoLoops().forEach((loopKey, loop) -> {
            loop.stop();
            Log.d(PpaassVpnService.class.getName(), "Destroy IOLoop:  " + loopKey);
        });
        try {
            this.vpnInputStream.close();
            this.vpnOutputStream.close();
            this.vpnInterface.close();
            Log.d(PpaassVpnService.class.getName(), "Close vpn service files.");
        } catch (IOException e) {
            Log.e(PpaassVpnService.class.getName(), "Close vpn service files exception happen.", e);
        }
    }
}
