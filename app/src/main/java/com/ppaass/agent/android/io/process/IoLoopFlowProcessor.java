package com.ppaass.agent.android.io.process;

import android.net.VpnService;
import android.util.Log;
import com.ppaass.agent.android.IPpaassConstant;
import com.ppaass.agent.android.io.process.common.VpnNioSocketChannel;
import com.ppaass.common.constant.ICommonConstant;
import com.ppaass.common.handler.AgentMessageEncoder;
import com.ppaass.common.handler.PrintExceptionHandler;
import com.ppaass.common.handler.ProxyMessageDecoder;
import com.ppaass.common.log.IPpaassLogger;
import com.ppaass.common.log.PpaassLoggerFactory;
import com.ppaass.protocol.base.ip.IpDataProtocol;
import com.ppaass.protocol.base.ip.IpPacket;
import com.ppaass.protocol.base.ip.IpV4Header;
import com.ppaass.protocol.base.tcp.TcpHeader;
import com.ppaass.protocol.base.tcp.TcpPacket;
import com.ppaass.protocol.base.udp.UdpHeader;
import com.ppaass.protocol.base.udp.UdpPacket;
import com.ppaass.protocol.common.util.UUIDUtil;
import com.ppaass.protocol.vpn.message.AgentMessage;
import com.ppaass.protocol.vpn.message.AgentMessageBody;
import com.ppaass.protocol.vpn.message.AgentMessageBodyType;
import com.ppaass.protocol.vpn.message.EncryptionType;
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
import org.apache.commons.pool2.impl.AbandonedConfig;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class IoLoopFlowProcessor {
    private static final IPpaassLogger logger = PpaassLoggerFactory.INSTANCE.getLogger();
    private static final int THREAD_NUMBER = 16;
    private Bootstrap proxyTcpChannelBootstrap;
    private final ConcurrentMap<String, TcpIoLoop> tcpIoLoops;
    private final OutputStream remoteToDeviceStream;
    private final byte[] agentPrivateKeyBytes;
    private final byte[] proxyPublicKeyBytes;
    private final ReentrantReadWriteLock reentrantReadWriteLock;
    private GenericObjectPool<Channel> proxyTcpChannelPool;
    private final VpnService vpnService;

    public IoLoopFlowProcessor(VpnService vpnService, OutputStream remoteToDeviceStream, byte[] agentPrivateKeyBytes,
                               byte[] proxyPublicKeyBytes) {
        this.agentPrivateKeyBytes = agentPrivateKeyBytes;
        this.proxyPublicKeyBytes = proxyPublicKeyBytes;
        this.vpnService = vpnService;
        this.tcpIoLoops = new ConcurrentHashMap<>();
        this.remoteToDeviceStream = remoteToDeviceStream;
        this.reentrantReadWriteLock = new ReentrantReadWriteLock();
    }

    public void shutdown() {
        this.destroyResources();
    }

    public void prepareResources() {
        try {
            this.reentrantReadWriteLock.writeLock().lock();
            try {
                this.reentrantReadWriteLock.writeLock().lock();
                this.proxyTcpChannelBootstrap = this.createProxyTcpChannelBootstrap(vpnService, remoteToDeviceStream);
                this.proxyTcpChannelPool = this.createProxyTcpChannelPool(this.proxyTcpChannelBootstrap);
            } finally {
                this.reentrantReadWriteLock.writeLock().unlock();
            }
        } finally {
            this.reentrantReadWriteLock.writeLock().unlock();
        }
    }

    private void destroyResources() {
        try {
            this.reentrantReadWriteLock.writeLock().lock();
            if (this.proxyTcpChannelBootstrap != null) {
                this.proxyTcpChannelBootstrap.config().group().shutdownGracefully();
            }
            if (this.proxyTcpChannelPool != null) {
                this.proxyTcpChannelPool.close();
                this.proxyTcpChannelPool.clear();
            }
            this.proxyTcpChannelBootstrap = null;
            this.proxyTcpChannelPool = null;
        } finally {
            this.reentrantReadWriteLock.writeLock().unlock();
        }
    }

    private GenericObjectPool<Channel> createProxyTcpChannelPool(Bootstrap proxyTcpChannelBootstrap) {
        ProxyTcpChannelFactory proxyTcpChannelFactory =
                new ProxyTcpChannelFactory(proxyTcpChannelBootstrap);
        GenericObjectPoolConfig<Channel> config = new GenericObjectPoolConfig<>();
        config.setMaxIdle(64);
        config.setMaxTotal(64);
        config.setMinIdle(32);
        config.setMaxWaitMillis(2000);
        config.setBlockWhenExhausted(true);
        config.setTestWhileIdle(true);
        config.setTestOnBorrow(true);
        config.setTestOnCreate(false);
        config.setTestOnReturn(true);
        config.setEvictionPolicy(new ProxyTcpChannelPoolEvictionPolicy());
        config.setTimeBetweenEvictionRunsMillis(60000);
        config.setMinEvictableIdleTimeMillis(-1);
        config.setSoftMinEvictableIdleTimeMillis(1800000);
        config.setNumTestsPerEvictionRun(-1);
        config.setJmxEnabled(false);
        GenericObjectPool<Channel> result = new GenericObjectPool<>(proxyTcpChannelFactory, config);
        AbandonedConfig abandonedConfig = new AbandonedConfig();
        abandonedConfig.setRemoveAbandonedOnMaintenance(true);
        abandonedConfig.setRemoveAbandonedOnBorrow(true);
        abandonedConfig.setRemoveAbandonedTimeout(Integer.MAX_VALUE);
        result.setAbandonedConfig(abandonedConfig);
        proxyTcpChannelFactory.attachPool(result);
//        try {
//            result.preparePool();
//        } catch (Exception e) {
//            PpaassLogger.INSTANCE
//                    .error(() -> "Fail to initialize proxy channel pool because of exception.", () -> new Object[]{e});
//            throw new PpaassException("Fail to initialize proxy channel pool.", e);
//        }
        return result;
    }

    private Bootstrap createProxyTcpChannelBootstrap(VpnService vpnService, OutputStream remoteToDeviceStream) {
        System.setProperty("io.netty.selectorAutoRebuildThreshold", Integer.toString(Integer.MAX_VALUE));
        Bootstrap proxyTcpChannelBootstrap = new Bootstrap();
        proxyTcpChannelBootstrap.group(new NioEventLoopGroup(THREAD_NUMBER));
        proxyTcpChannelBootstrap.channelFactory(() -> new VpnNioSocketChannel(vpnService));
        proxyTcpChannelBootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000);
        proxyTcpChannelBootstrap.option(ChannelOption.SO_KEEPALIVE, true);
        proxyTcpChannelBootstrap.option(ChannelOption.AUTO_READ, true);
        proxyTcpChannelBootstrap.option(ChannelOption.AUTO_CLOSE, false);
        proxyTcpChannelBootstrap.option(ChannelOption.TCP_NODELAY, true);
        proxyTcpChannelBootstrap.option(ChannelOption.SO_REUSEADDR, true);
        proxyTcpChannelBootstrap.remoteAddress(IPpaassConstant.PROXY_SERVER_ADDRESS, IPpaassConstant.PROXY_SERVER_PORT);
//        remoteBootstrap.option(ChannelOption.SO_LINGER, 1);
//        remoteBootstrap.option(ChannelOption.SO_RCVBUF, 131072);
//        remoteBootstrap.option(ChannelOption.SO_SNDBUF, 131072);
        proxyTcpChannelBootstrap.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel remoteChannel) {
                ChannelPipeline proxyChannelPipeline = remoteChannel.pipeline();
                proxyChannelPipeline.addLast(new Lz4FrameDecoder());
                proxyChannelPipeline.addLast(new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0,
                        ICommonConstant.LENGTH_FRAME_FIELD_BYTE_NUMBER, 0,
                        ICommonConstant.LENGTH_FRAME_FIELD_BYTE_NUMBER));
                proxyChannelPipeline.addLast(new ProxyMessageDecoder(IoLoopFlowProcessor.this.agentPrivateKeyBytes));
                proxyChannelPipeline.addLast(new IoLoopRemoteToDeviceHandler(remoteToDeviceStream, tcpIoLoops));
                proxyChannelPipeline.addLast(new Lz4FrameEncoder());
                proxyChannelPipeline.addLast(new LengthFieldPrepender(ICommonConstant.LENGTH_FRAME_FIELD_BYTE_NUMBER));
                proxyChannelPipeline.addLast(new AgentMessageEncoder(IoLoopFlowProcessor.this.proxyPublicKeyBytes));
                proxyChannelPipeline.addLast(PrintExceptionHandler.INSTANCE);
            }
        });
        return proxyTcpChannelBootstrap;
    }

    private TcpIoLoop getOrCreateTcpIoLoop(IpPacket ipPacket, Channel proxyTcpChannel) {
        IpV4Header ipV4Header = (IpV4Header) ipPacket.getHeader();
        TcpPacket tcpPacket = (TcpPacket) ipPacket.getData();
        final String tcpIoLoopKey =
                IoLoopUtil.INSTANCE.generateIoLoopKey(IpDataProtocol.TCP, ipV4Header.getSourceAddress(),
                        tcpPacket.getHeader().getSourcePort()
                        , ipV4Header.getDestinationAddress(), tcpPacket.getHeader().getDestinationPort()
                );
        return this.tcpIoLoops.computeIfAbsent(tcpIoLoopKey,
                (key) -> {
                    TcpIoLoop tcpIoLoop =
                            new TcpIoLoop(key, System.currentTimeMillis(),
                                    ipV4Header.getSourceAddress(),
                                    ipV4Header.getDestinationAddress(),
                                    tcpPacket.getHeader().getSourcePort(),
                                    tcpPacket.getHeader().getDestinationPort(), this.tcpIoLoops, proxyTcpChannelPool);
                    tcpIoLoop.setStatus(TcpIoLoopStatus.LISTEN);
                    tcpIoLoop.setProxyTcpChannel(proxyTcpChannel);
//                    TcpHeaderOption mssOption = null;
//                    for (TcpHeaderOption option : tcpHeader.getOptions()) {
//                        if (option.getKind() == TcpHeaderOption.Kind.MSS) {
//                            mssOption = option;
//                            break;
//                        }
//                    }
//                    if (mssOption != null) {
//                        ByteBuf mssOptionBuf = Unpooled.wrappedBuffer(mssOption.getInfo());
//                        int mss = mssOptionBuf.readUnsignedShort();
//                        tcpIoLoop.setMss(mss);
//                        Optional<TcpHeaderOption> wsoptOptional = tcpHeader.getOptions().stream()
//                                .filter(tcpHeaderOption -> tcpHeaderOption.getKind() ==
//                                        TcpHeaderOption.Kind.WSOPT)
//                                .findFirst();
//                        wsoptOptional.ifPresent(tcpHeaderOption -> {
//                            int window = tcpHeader.getWindow();
//                            ByteBuf wsoptBuf = Unpooled.wrappedBuffer(tcpHeaderOption.getInfo());
//                            int wsopt = wsoptBuf.readByte();
//                            tcpIoLoop.setConcreteWindowSizeInByte(window << wsopt);
//                        });
//                    }
                    Log.d(IoLoopFlowProcessor.class.getName(),
                            "Create tcp loop, ip packet = " + ipPacket + ", tcp loop = " + tcpIoLoop +
                                    ", loop container size = " + tcpIoLoops.size());
                    return tcpIoLoop;
                });
    }

    public void executeUdp(IpPacket inputIpPacket) {
        IpV4Header inputIpV4Header = (IpV4Header) inputIpPacket.getHeader();
        UdpPacket inputUdpPacket = (UdpPacket) inputIpPacket.getData();
        UdpHeader inputUdpHeader = inputUdpPacket.getHeader();
        final InetAddress destinationAddress;
        try {
            destinationAddress = InetAddress.getByAddress(inputIpV4Header.getDestinationAddress());
        } catch (UnknownHostException e) {
            return;
        }
        final InetAddress sourceAddress;
        try {
            sourceAddress = InetAddress.getByAddress(inputIpV4Header.getSourceAddress());
        } catch (UnknownHostException e) {
            return;
        }
        AgentMessageBody agentMessageBody =
                new AgentMessageBody(
                        UUIDUtil.INSTANCE.generateUuid(),
                        IPpaassConstant.AGENT_INSTANCE_ID,
                        IPpaassConstant.USER_TOKEN,
                        sourceAddress.getHostAddress(),
                        inputUdpHeader.getSourcePort(),
                        destinationAddress.getHostAddress(),
                        inputUdpHeader.getDestinationPort(),
                        AgentMessageBodyType.UDP_DATA,
                        IoLoopUtil.INSTANCE.generateIoLoopKey(IpDataProtocol.UDP, sourceAddress.getAddress(),
                                inputUdpHeader.getSourcePort(),
                                destinationAddress.getAddress(), inputUdpHeader.getDestinationPort()),
                        null,
                        inputUdpPacket.getData());
        AgentMessage agentMessage =
                new AgentMessage(
                        UUIDUtil.INSTANCE.generateUuidInBytes(),
                        EncryptionType.choose(),
                        agentMessageBody);
        Channel remoteUdpChannel = null;
        try {
            remoteUdpChannel = this.proxyTcpChannelPool.borrowObject();
        } catch (Exception e) {
            return;
        }
        logger.debug(() -> "Write UDP data to proxy, agent message:\n{}\n", () -> new Object[]{
                agentMessage
        });
        remoteUdpChannel.writeAndFlush(agentMessage);
    }

    public void executeTcp(IpPacket inputIpPacket) {
        TcpPacket inputTcpPacket = (TcpPacket) inputIpPacket.getData();
        TcpHeader inputTcpHeader = inputTcpPacket.getHeader();
        try {
            if (inputTcpHeader.isSyn()) {
                doSyn(inputIpPacket);
                return;
            }
            if (inputTcpHeader.isAck()) {
                doAck(inputIpPacket);
                return;
            }
            if ((inputTcpHeader.isFin())) {
                doFin(inputIpPacket);
                return;
            }
            if (inputTcpHeader.isRst()) {
                doRst(inputIpPacket);
            }
        } catch (Exception e) {
            Log.e(IoLoopFlowProcessor.class.getName(),
                    "Exception happen when execute ip packet, ip packet = " + inputIpPacket, e);
        }
    }

    private void doSyn(IpPacket inputIpPacket) {
        IpV4Header inputIpV4Header = (IpV4Header) inputIpPacket.getHeader();
        TcpPacket inputTcpPacket = (TcpPacket) inputIpPacket.getData();
        TcpHeader inputTcpHeader = inputTcpPacket.getHeader();
        final String tcpIoLoopKey =
                IoLoopUtil.INSTANCE.generateIoLoopKey(IpDataProtocol.TCP, inputIpV4Header.getSourceAddress(),
                        inputTcpHeader.getSourcePort()
                        , inputIpV4Header.getDestinationAddress(), inputTcpHeader.getDestinationPort()
                );
        TcpIoLoop existingTcpIoLoop = this.tcpIoLoops.get(tcpIoLoopKey);
        if (existingTcpIoLoop != null) {
            IpPacket ackPacket = TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildAck(
                    inputIpV4Header.getDestinationAddress(),
                    existingTcpIoLoop.getDestinationPort(),
                    inputIpV4Header.getSourceAddress(),
                    existingTcpIoLoop.getSourcePort(),
                    inputTcpHeader.getAcknowledgementNumber(),
                    inputTcpHeader.getSequenceNumber(),
                    null
            );
            TcpIoLoopRemoteToDeviceWriter.INSTANCE.writeIpPacketToDevice(null, ackPacket, tcpIoLoopKey,
                    this.remoteToDeviceStream);
            Log.d(IoLoopFlowProcessor.class.getName(),
                    "Duplicate syn request coming, ack and ignore it, ip packet = " +
                            inputIpPacket + ", tcp loop key = " + tcpIoLoopKey);
            return;
        }
        Channel proxyTcpChannel = null;
        try {
            proxyTcpChannel = this.proxyTcpChannelPool.borrowObject();
        } catch (Exception e) {
            IpPacket ipPacketWroteToDevice =
                    TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildFinAck(
                            inputIpV4Header.getDestinationAddress(),
                            inputTcpHeader.getDestinationPort(),
                            inputIpV4Header.getSourceAddress(),
                            inputTcpHeader.getSourcePort(),
                            inputTcpHeader.getAcknowledgementNumber(),
                            inputTcpHeader.getSequenceNumber());
            TcpIoLoopRemoteToDeviceWriter.INSTANCE
                    .writeIpPacketToDevice(null, ipPacketWroteToDevice, null,
                            remoteToDeviceStream);
            return;
        }
        TcpIoLoop tcpIoLoop = getOrCreateTcpIoLoop(inputIpPacket, proxyTcpChannel);
        if (tcpIoLoop == null) {
            Log.e(IoLoopFlowProcessor.class.getName(),
                    "RECEIVE [SYN], initialize connection FAIL (2), tcp header ="
                            + inputTcpHeader + " tcp loop key = " + tcpIoLoopKey);
            IpPacket ipPacketWroteToDevice =
                    TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildFinAck(
                            inputIpV4Header.getDestinationAddress(),
                            inputTcpHeader.getDestinationPort(),
                            inputIpV4Header.getSourceAddress(),
                            inputTcpHeader.getSourcePort(),
                            inputTcpHeader.getAcknowledgementNumber(),
                            inputTcpHeader.getSequenceNumber());
            TcpIoLoopRemoteToDeviceWriter.INSTANCE
                    .writeIpPacketToDevice(null, ipPacketWroteToDevice, null,
                            remoteToDeviceStream);
            return;
        }
        tcpIoLoop.setAccumulateRemoteToDeviceAcknowledgementNumber(
                inputTcpHeader.getSequenceNumber());
        tcpIoLoop.increaseAccumulateRemoteToDeviceAcknowledgementNumber(1);
        AgentMessageBody agentMessageBody = new AgentMessageBody(
                UUIDUtil.INSTANCE.generateUuid(),
                IPpaassConstant.AGENT_INSTANCE_ID,
                IPpaassConstant.USER_TOKEN,
                tcpIoLoop.getSourceAddress().getHostAddress(),
                tcpIoLoop.getSourcePort(),
                tcpIoLoop.getDestinationAddress().getHostAddress(),
                tcpIoLoop.getDestinationPort(),
                AgentMessageBodyType.TCP_CONNECT,
                tcpIoLoop.getKey(),
                null,
                null);
        AgentMessage agentMessage = new AgentMessage(
                UUIDUtil.INSTANCE.generateUuidInBytes(),
                EncryptionType.choose(),
                agentMessageBody);
        proxyTcpChannel.writeAndFlush(agentMessage);
    }

    private void doAck(IpPacket inputIpPacket) {
        IpV4Header inputIpV4Header = (IpV4Header) inputIpPacket.getHeader();
        TcpPacket inputTcpPacket = (TcpPacket) inputIpPacket.getData();
        TcpHeader inputTcpHeader = inputTcpPacket.getHeader();
        byte[] data = inputTcpPacket.getData();
        final String tcpIoLoopKey =
                IoLoopUtil.INSTANCE.generateIoLoopKey(IpDataProtocol.TCP, inputIpV4Header.getSourceAddress(),
                        inputTcpHeader.getSourcePort()
                        , inputIpV4Header.getDestinationAddress(), inputTcpHeader.getDestinationPort()
                );
        TcpIoLoop tcpIoLoop = this.tcpIoLoops.get(tcpIoLoopKey);
        if (tcpIoLoop == null) {
            if (inputTcpHeader.isFin()) {
                Log.d(IoLoopFlowProcessor.class.getName(),
                        "RECEIVE [FIN ACK(LOOP NOT EXIST, only do FIN ACK)], close directly, tcp header =" +
                                inputTcpHeader);
                IpPacket finAck = TcpIoLoopRemoteToDeviceWriter.INSTANCE
                        .buildFinAck(inputIpV4Header.getDestinationAddress(), inputTcpHeader.getDestinationPort(),
                                inputIpV4Header.getSourceAddress(), inputTcpHeader.getSourcePort(),
                                inputTcpHeader.getAcknowledgementNumber(),
                                inputTcpHeader.getSequenceNumber() + 1);
                TcpIoLoopRemoteToDeviceWriter.INSTANCE
                        .writeIpPacketToDevice(null, finAck, tcpIoLoopKey, this.remoteToDeviceStream);
                return;
            }
            if (inputTcpHeader.isRst()) {
                // Do nothing
                Log.d(IoLoopFlowProcessor.class.getName(),
                        "RECEIVE [RST ACK(LOOP NOT EXIST)], just ignore, tcp header =" + inputTcpHeader +
                                ", tcp loop = " + tcpIoLoop);
                return;
            }
            //Ignore ack on loop not exist
            Log.d(IoLoopFlowProcessor.class.getName(),
                    "RECEIVE [ACK(LOOP NOT EXIST)], just ignore, tcp header =" + inputTcpHeader +
                            ", tcp loop = " + tcpIoLoop);
            return;
        }
        if (inputTcpHeader.isFin()) {
            //Confirm the ack is not for FIN first.
            if (TcpIoLoopStatus.ESTABLISHED == tcpIoLoop.getStatus()) {
                Log.d(IoLoopFlowProcessor.class.getName(),
                        "RECEIVE [FIN ACK on ESTABLISHED], response FIN ACK and destory the tcp loop, data size = " +
                                data.length + ", tcp header =" + inputTcpHeader +
                                ", tcp loop = " + tcpIoLoop);
                tcpIoLoop.increaseAccumulateRemoteToDeviceAcknowledgementNumber(1);
                IpPacket ackForFinAck = TcpIoLoopRemoteToDeviceWriter.INSTANCE
                        .buildFinAck(inputIpV4Header.getDestinationAddress(), inputTcpHeader.getDestinationPort(),
                                inputIpV4Header.getSourceAddress(), inputTcpHeader.getSourcePort(),
                                tcpIoLoop.getAccumulateRemoteToDeviceSequenceNumber(),
                                tcpIoLoop.getAccumulateRemoteToDeviceAcknowledgementNumber());
                TcpIoLoopRemoteToDeviceWriter.INSTANCE
                        .writeIpPacketToDevice(null, ackForFinAck, tcpIoLoopKey, this.remoteToDeviceStream);
                tcpIoLoop.destroy();
                return;
            }
            if (TcpIoLoopStatus.FIN_WAITE2 == tcpIoLoop.getStatus()) {
                Log.d(IoLoopFlowProcessor.class.getName(),
                        "RECEIVE [FIN ACK on FIN_WAITE2], mark status to TIME_WAITE, do resend data size = " +
                                data.length + ", tcp header =" + inputTcpHeader +
                                ", tcp loop = " + tcpIoLoop);
                tcpIoLoop.setStatus(TcpIoLoopStatus.TIME_WAITE);
                tcpIoLoop.increaseAccumulateRemoteToDeviceAcknowledgementNumber(1);
                IpPacket ackForFinAck = TcpIoLoopRemoteToDeviceWriter.INSTANCE
                        .buildAck(inputIpV4Header.getDestinationAddress(), inputTcpHeader.getDestinationPort(),
                                inputIpV4Header.getSourceAddress(), inputTcpHeader.getSourcePort(),
                                tcpIoLoop.getAccumulateRemoteToDeviceSequenceNumber(),
                                tcpIoLoop.getAccumulateRemoteToDeviceAcknowledgementNumber(),
                                null);
                TcpIoLoopRemoteToDeviceWriter.INSTANCE
                        .writeIpPacketToDevice(null, ackForFinAck, tcpIoLoopKey, this.remoteToDeviceStream);
                tcpIoLoop.destroy();
                return;
            }
            Log.d(IoLoopFlowProcessor.class.getName(),
                    "RECEIVE [FIN ACK on " + tcpIoLoop.getStatus() + "], ILLEGAL STATUS just ignore, data size = " +
                            data.length +
                            ", tcp header =" + inputTcpHeader +
                            ", tcp loop = " + tcpIoLoop);
            return;
        }
        if (TcpIoLoopStatus.SYN_RECEIVED == tcpIoLoop.getStatus()) {
            //Receive the ack of syn_ack.
            tcpIoLoop.setStatus(TcpIoLoopStatus.ESTABLISHED);
            Log.d(IoLoopFlowProcessor.class.getName(),
                    "RECEIVE [ACK FOR SYN_ACK], switch tcp loop to ESTABLISHED, tcp header =" + inputTcpHeader +
                            ", tcp loop = " + tcpIoLoop);
            return;
        }
        if (TcpIoLoopStatus.ESTABLISHED == tcpIoLoop.getStatus()) {
            if (data.length == 0) {
                //A ack for previous remote data
                Log.d(IoLoopFlowProcessor.class.getName(),
                        "RECEIVE [ACK, WITHOUT DATA(" + (inputTcpHeader.isPsh() ? "PSH , " : "") +
                                "status=ESTABLISHED, size=0)], tcp header =" +
                                inputTcpHeader +
                                ", tcp loop = " + tcpIoLoop);
                return;
            }
            AgentMessageBody agentMessageBody = new AgentMessageBody(
                    UUIDUtil.INSTANCE.generateUuid(),
                    IPpaassConstant.AGENT_INSTANCE_ID,
                    IPpaassConstant.USER_TOKEN,
                    tcpIoLoop.getSourceAddress().getHostAddress(),
                    tcpIoLoop.getSourcePort(),
                    tcpIoLoop.getDestinationAddress().getHostAddress(),
                    inputTcpHeader.getDestinationPort(),
                    AgentMessageBodyType.TCP_DATA,
                    tcpIoLoop.getKey(),
                    null,
                    data);
            AgentMessage agentMessage = new AgentMessage(
                    UUIDUtil.INSTANCE.generateUuidInBytes(),
                    EncryptionType.choose(),
                    agentMessageBody);
            tcpIoLoop.increaseAccumulateRemoteToDeviceAcknowledgementNumber(data.length);
            tcpIoLoop.getProxyTcpChannel().writeAndFlush(agentMessage).syncUninterruptibly();
            Log.d(IoLoopFlowProcessor.class.getName(),
                    "RECEIVE [ACK WITH DATA(" + (inputTcpHeader.isPsh() ? "PSH , " : "") + "status=ESTABLISHED, size=" +
                            data.length +
                            ")], write data to remote, tcp header =" +
                            inputTcpHeader +
                            ", tcp loop = " + tcpIoLoop + ", DATA: \n" +
                            ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(data
                            )));
            return;
        }
        if (TcpIoLoopStatus.CLOSE_WAIT == tcpIoLoop.getStatus()) {
            //A ack for previous remote data
            Log.d(IoLoopFlowProcessor.class.getName(),
                    "RECEIVE [ACK on CLOSE_WAIT, WITHOUT DATA (" + (inputTcpHeader.isPsh() ? "PSH , " : "") +
                            "status=ESTABLISHED, size=0)], tcp header =" +
                            inputTcpHeader +
                            ", tcp loop = " + tcpIoLoop);
            tcpIoLoop.getProxyTcpChannel().close().addListener(future -> {
                tcpIoLoop.increaseAccumulateRemoteToDeviceSequenceNumber(1);
                IpPacket finAck = TcpIoLoopRemoteToDeviceWriter.INSTANCE
                        .buildFinAck(inputIpV4Header.getDestinationAddress(),
                                inputTcpHeader.getDestinationPort(),
                                inputIpV4Header.getSourceAddress(), inputTcpHeader.getSourcePort(),
                                tcpIoLoop.getAccumulateRemoteToDeviceSequenceNumber(),
                                tcpIoLoop.getAccumulateRemoteToDeviceAcknowledgementNumber());
                TcpIoLoopRemoteToDeviceWriter.INSTANCE
                        .writeIpPacketToDevice(null, finAck, tcpIoLoopKey, this.remoteToDeviceStream);
                tcpIoLoop.setStatus(TcpIoLoopStatus.LAST_ACK);
            });
            return;
        }
        if (TcpIoLoopStatus.LAST_ACK == tcpIoLoop.getStatus()) {
            Log.d(IoLoopFlowProcessor.class.getName(),
                    "RECEIVE [ACK on LAST_ACK], close tcp loop, tcp header ="
                            + inputTcpHeader + " tcp loop = " + tcpIoLoop);
            tcpIoLoop.increaseAccumulateRemoteToDeviceSequenceNumber(1);
            tcpIoLoop.destroy();
            return;
        }
        if (TcpIoLoopStatus.FIN_WAITE1 == tcpIoLoop.getStatus()) {
            Log.d(IoLoopFlowProcessor.class.getName(),
                    "RECEIVE [ACK on FIN_WAITE1], switch tcp loop status to FIN_WAITE2, tcp header ="
                            + inputTcpHeader + " tcp loop = " + tcpIoLoop);
            tcpIoLoop.increaseAccumulateRemoteToDeviceSequenceNumber(1);
            tcpIoLoop.setStatus(TcpIoLoopStatus.FIN_WAITE2);
            return;
        }
        Log.e(IoLoopFlowProcessor.class.getName(),
                "RECEIVE [ACK(status=" + tcpIoLoop.getStatus() + ", size=" + (data == null ? 0 : data.length) +
                        ")], Tcp loop in ILLEGAL STATUS, ignore current packet do nothing just ack, tcp header ="
                        + inputTcpHeader + " tcp loop = " + tcpIoLoop);
    }

    private void doRst(IpPacket inputIpPacket) {
        IpV4Header inputIpV4Header = (IpV4Header) inputIpPacket.getHeader();
        TcpPacket inputTcpPacket = (TcpPacket) inputIpPacket.getData();
        TcpHeader inputTcpHeader = inputTcpPacket.getHeader();
        final String tcpIoLoopKey =
                IoLoopUtil.INSTANCE.generateIoLoopKey(IpDataProtocol.TCP, inputIpV4Header.getSourceAddress(),
                        inputTcpHeader.getSourcePort()
                        , inputIpV4Header.getDestinationAddress(), inputTcpHeader.getDestinationPort()
                );
        TcpIoLoop tcpIoLoop = this.tcpIoLoops.get(tcpIoLoopKey);
        if (tcpIoLoop == null) {
            Log.d(IoLoopFlowProcessor.class.getName(),
                    "RECEIVE [RST], destroy tcp loop(NOT EXIST), ip packet =" +
                            inputIpPacket +
                            ", tcp loop key = '" + tcpIoLoopKey + "'");
            return;
        }
        Log.d(IoLoopFlowProcessor.class.getName(),
                "RECEIVE [RST], destroy tcp loop, ip packet =" +
                        inputIpPacket +
                        ", tcp loop = " + tcpIoLoop);
        tcpIoLoop.destroy();
    }

    private void doFin(IpPacket inputIpPacket) {
        IpV4Header inputIpV4Header = (IpV4Header) inputIpPacket.getHeader();
        TcpPacket inputTcpPacket = (TcpPacket) inputIpPacket.getData();
        TcpHeader inputTcpHeader = inputTcpPacket.getHeader();
        final String tcpIoLoopKey =
                IoLoopUtil.INSTANCE.generateIoLoopKey(IpDataProtocol.TCP, inputIpV4Header.getSourceAddress(),
                        inputTcpHeader.getSourcePort()
                        , inputIpV4Header.getDestinationAddress(), inputTcpHeader.getDestinationPort()
                );
        TcpIoLoop tcpIoLoop = this.tcpIoLoops.get(tcpIoLoopKey);
        if (tcpIoLoop == null) {
            Log.d(IoLoopFlowProcessor.class.getName(),
                    "RECEIVE [FIN(LOOP NOT EXIST, STEP1)], send ACK directly, tcp header =" +
                            inputTcpHeader +
                            ", tcp loop = " + tcpIoLoop);
            IpPacket finAck1 = TcpIoLoopRemoteToDeviceWriter.INSTANCE
                    .buildAck(inputIpV4Header.getDestinationAddress(), inputTcpHeader.getDestinationPort(),
                            inputIpV4Header.getSourceAddress(), inputTcpHeader.getSourcePort(),
                            inputTcpHeader.getAcknowledgementNumber(),
                            inputTcpHeader.getSequenceNumber() + 1, null);
            TcpIoLoopRemoteToDeviceWriter.INSTANCE
                    .writeIpPacketToDevice(null, finAck1, tcpIoLoopKey, this.remoteToDeviceStream);
            Log.d(IoLoopFlowProcessor.class.getName(),
                    "RECEIVE [FIN(LOOP NOT EXIST, STEP2)], send FIN ACK directly, tcp header =" +
                            inputTcpHeader +
                            ", tcp loop = " + tcpIoLoop);
            IpPacket finAck2 = TcpIoLoopRemoteToDeviceWriter.INSTANCE
                    .buildFinAck(inputIpV4Header.getDestinationAddress(),
                            inputTcpHeader.getDestinationPort(),
                            inputIpV4Header.getSourceAddress(), inputTcpHeader.getSourcePort(),
                            inputTcpHeader.getAcknowledgementNumber(),
                            inputTcpHeader.getSequenceNumber() + 1);
            TcpIoLoopRemoteToDeviceWriter.INSTANCE
                    .writeIpPacketToDevice(null, finAck2, tcpIoLoopKey, this.remoteToDeviceStream);
            return;
        }
        if (TcpIoLoopStatus.ESTABLISHED == tcpIoLoop.getStatus()) {
            Log.d(IoLoopFlowProcessor.class.getName(),
                    "RECEIVE [FIN(status=ESTABLISHED, STEP1)], switch tcp loop status to CLOSE_WAIT, tcp header =" +
                            inputTcpHeader +
                            ", tcp loop = " + tcpIoLoop);
            tcpIoLoop.setStatus(TcpIoLoopStatus.CLOSE_WAIT);
            tcpIoLoop.increaseAccumulateRemoteToDeviceAcknowledgementNumber(1);
            IpPacket finAck1 = TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildAck(
                    tcpIoLoop.getDestinationAddress().getAddress(),
                    tcpIoLoop.getDestinationPort(),
                    tcpIoLoop.getSourceAddress().getAddress(),
                    tcpIoLoop.getSourcePort(),
                    tcpIoLoop.getAccumulateRemoteToDeviceSequenceNumber(),
                    tcpIoLoop.getAccumulateRemoteToDeviceAcknowledgementNumber(),
                    null);
            TcpIoLoopRemoteToDeviceWriter.INSTANCE
                    .writeIpPacketToDevice(null, finAck1, tcpIoLoop.getKey(),
                            this.remoteToDeviceStream);
            tcpIoLoop.destroy();
            return;
        }
        Log.e(IoLoopFlowProcessor.class.getName(),
                "RECEIVE [FIN(status=" + tcpIoLoop.getStatus() +
                        ")], Tcp loop in ILLEGAL STATUS, ignore current packet do nothing, tcp header ="
                        + inputTcpHeader + " tcp loop = " + tcpIoLoop);
    }
}
