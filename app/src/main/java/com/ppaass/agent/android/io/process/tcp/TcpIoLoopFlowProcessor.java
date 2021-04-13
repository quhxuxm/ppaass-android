package com.ppaass.agent.android.io.process.tcp;

import android.net.VpnService;
import android.util.Log;
import com.ppaass.agent.android.io.process.common.VpnNioSocketChannel;
import com.ppaass.common.cryptography.EncryptionType;
import com.ppaass.common.handler.AgentMessageEncoder;
import com.ppaass.common.handler.PrintExceptionHandler;
import com.ppaass.common.handler.ProxyMessageDecoder;
import com.ppaass.common.message.AgentMessage;
import com.ppaass.common.message.AgentMessageBody;
import com.ppaass.common.message.AgentMessageBodyType;
import com.ppaass.common.message.MessageSerializer;
import com.ppaass.protocol.base.ip.IpPacket;
import com.ppaass.protocol.base.ip.IpV4Header;
import com.ppaass.protocol.base.tcp.TcpHeader;
import com.ppaass.protocol.base.tcp.TcpHeaderOption;
import com.ppaass.protocol.base.tcp.TcpPacket;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.compression.Lz4FrameDecoder;
import io.netty.handler.codec.compression.Lz4FrameEncoder;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.ppaass.agent.android.io.process.tcp.ITcpIoLoopConstant.TCP_IO_LOOP_KEY_FORMAT;
import static com.ppaass.agent.android.io.process.tcp.ITcpIoLoopConstant.TCP_LOOP;

public class TcpIoLoopFlowProcessor {
    private final Bootstrap remoteBootstrap;
    private final ConcurrentMap<String, TcpIoLoop> tcpIoLoops;
    private final OutputStream remoteToDeviceStream;
    private final byte[] agentPrivateKeyBytes;
    private final byte[] proxyPublicKeyBytes;

    public TcpIoLoopFlowProcessor(VpnService vpnService, OutputStream remoteToDeviceStream, byte[] agentPrivateKeyBytes,
                                  byte[] proxyPublicKeyBytes) {
        this.agentPrivateKeyBytes = agentPrivateKeyBytes;
        this.proxyPublicKeyBytes = proxyPublicKeyBytes;
        this.remoteBootstrap = this.createRemoteBootstrap(vpnService, remoteToDeviceStream);
        this.tcpIoLoops = new ConcurrentHashMap<>();
        this.remoteToDeviceStream = remoteToDeviceStream;
    }

    public void shutdown() {
        this.remoteBootstrap.config().group().shutdownGracefully();
    }

    private Bootstrap createRemoteBootstrap(VpnService vpnService, OutputStream remoteToDeviceStream) {
//        System.setProperty("io.netty.selectorAutoRebuildThreshold", Integer.toString(Integer.MAX_VALUE));
        Bootstrap remoteBootstrap = new Bootstrap();
        remoteBootstrap.group(new NioEventLoopGroup(16));
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
                proxyChannelPipeline.addLast(new ProxyMessageDecoder(TcpIoLoopFlowProcessor.this.agentPrivateKeyBytes));
                proxyChannelPipeline.addLast(new TcpIoLoopRemoteToDeviceHandler());
                proxyChannelPipeline.addLast(new Lz4FrameEncoder());
                proxyChannelPipeline.addLast(new LengthFieldPrepender(4));
                proxyChannelPipeline.addLast(new AgentMessageEncoder(TcpIoLoopFlowProcessor.this.proxyPublicKeyBytes));
                proxyChannelPipeline.addLast(new PrintExceptionHandler());
            }
        });
        remoteBootstrap.attr(ITcpIoLoopConstant.REMOTE_TO_DEVICE_STREAM, remoteToDeviceStream);
        return remoteBootstrap;
    }

    private String generateLoopKey(byte[] sourceAddressInBytes, int sourcePort, byte[] destinationAddressInBytes,
                                   int destinationPort) {
        try {
            return String.format(TCP_IO_LOOP_KEY_FORMAT, InetAddress.getByAddress(sourceAddressInBytes), sourcePort,
                    InetAddress.getByAddress(destinationAddressInBytes), destinationPort);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private TcpIoLoop getOrCreateTcpIoLoop(IpPacket ipPacket, Channel remoteChannel) {
        IpV4Header ipV4Header = (IpV4Header) ipPacket.getHeader();
        TcpPacket tcpPacket = (TcpPacket) ipPacket.getData();
        TcpHeader tcpHeader = tcpPacket.getHeader();
        final String tcpIoLoopKey =
                this.generateLoopKey(ipV4Header.getSourceAddress(), tcpPacket.getHeader().getSourcePort()
                        , ipV4Header.getDestinationAddress(), tcpPacket.getHeader().getDestinationPort()
                );
        return this.tcpIoLoops.computeIfAbsent(tcpIoLoopKey,
                (key) -> {
                    TcpIoLoop tcpIoLoop =
                            new TcpIoLoop(key, System.currentTimeMillis(),
                                    ipV4Header.getSourceAddress(),
                                    ipV4Header.getDestinationAddress(),
                                    tcpPacket.getHeader().getSourcePort(),
                                    tcpPacket.getHeader().getDestinationPort(), this.tcpIoLoops);
                    tcpIoLoop.setStatus(TcpIoLoopStatus.LISTEN);
                    tcpIoLoop.setRemoteChannel(remoteChannel);
                    TcpHeaderOption mssOption = null;
                    for (TcpHeaderOption option : tcpHeader.getOptions()) {
                        if (option.getKind() == TcpHeaderOption.Kind.MSS) {
                            mssOption = option;
                            break;
                        }
                    }
                    if (mssOption != null) {
                        ByteBuf mssOptionBuf = Unpooled.wrappedBuffer(mssOption.getInfo());
                        int mss = mssOptionBuf.readUnsignedShort();
                        tcpIoLoop.setMss(mss);
                        Optional<TcpHeaderOption> wsoptOptional = tcpHeader.getOptions().stream()
                                .filter(tcpHeaderOption -> tcpHeaderOption.getKind() ==
                                        TcpHeaderOption.Kind.WSOPT)
                                .findFirst();
                        wsoptOptional.ifPresent(tcpHeaderOption -> {
                            int window = tcpHeader.getWindow();
                            ByteBuf wsoptBuf = Unpooled.wrappedBuffer(tcpHeaderOption.getInfo());
                            int wsopt = wsoptBuf.readByte();
                            tcpIoLoop.setConcreteWindowSizeInByte(window << wsopt);
                        });
                    }
                    Log.d(TcpIoLoopFlowProcessor.class.getName(),
                            "Create tcp loop, ip packet = " + ipPacket + ", tcp loop = " + tcpIoLoop +
                                    ", loop container size = " + tcpIoLoops.size());
                    return tcpIoLoop;
                });
    }

    public void execute(IpPacket inputIpPacket) {
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
            Log.e(TcpIoLoopFlowProcessor.class.getName(),
                    "Exception happen when execute ip packet, ip packet = " + inputIpPacket, e);
        }
    }

    private void doSyn(IpPacket inputIpPacket) {
        IpV4Header inputIpV4Header = (IpV4Header) inputIpPacket.getHeader();
        TcpPacket inputTcpPacket = (TcpPacket) inputIpPacket.getData();
        TcpHeader inputTcpHeader = inputTcpPacket.getHeader();
        final String tcpIoLoopKey =
                this.generateLoopKey(inputIpV4Header.getSourceAddress(), inputTcpHeader.getSourcePort()
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
            Log.d(TcpIoLoopFlowProcessor.class.getName(),
                    "Duplicate syn request coming, ack and ignore it, ip packet = " +
                            inputIpPacket + ", tcp loop key = " + tcpIoLoopKey);
            return;
        }
        this.remoteBootstrap
                .connect("45.63.92.64", 80)
                .addListener(
                        (ChannelFutureListener) connectResultFuture -> {
                            if (!connectResultFuture.isSuccess()) {
                                Log.e(TcpIoLoopFlowProcessor.class.getName(),
                                        "RECEIVE [SYN], initialize connection FAIL (1), tcp header ="
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
                            Channel remoteChannel = connectResultFuture.channel();
                            TcpIoLoop tcpIoLoop = getOrCreateTcpIoLoop(inputIpPacket, remoteChannel);
                            if (tcpIoLoop == null) {
                                Log.e(TcpIoLoopFlowProcessor.class.getName(),
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
                            tcpIoLoop.getRemoteChannel().attr(TCP_LOOP).setIfAbsent(tcpIoLoop);
                            AgentMessageBody agentMessageBody = new AgentMessageBody(
                                    MessageSerializer.INSTANCE.generateUuid(),
                                    "DEFAULT_USER_TOKEN", tcpIoLoop.getDestinationAddress().getHostAddress(),
                                    tcpIoLoop.getDestinationPort(),
                                    AgentMessageBodyType.CONNECT_WITH_KEEP_ALIVE,
                                    new byte[]{});
                            AgentMessage agentMessage = new AgentMessage(
                                    MessageSerializer.INSTANCE.generateUuidInBytes(),
                                    EncryptionType.choose(),
                                    agentMessageBody);
                            remoteChannel.writeAndFlush(agentMessage);
                        });
    }

    private void doAck(IpPacket inputIpPacket) {
        IpV4Header inputIpV4Header = (IpV4Header) inputIpPacket.getHeader();
        TcpPacket inputTcpPacket = (TcpPacket) inputIpPacket.getData();
        TcpHeader inputTcpHeader = inputTcpPacket.getHeader();
        byte[] data = inputTcpPacket.getData();
        final String tcpIoLoopKey =
                this.generateLoopKey(inputIpV4Header.getSourceAddress(), inputTcpHeader.getSourcePort()
                        , inputIpV4Header.getDestinationAddress(), inputTcpHeader.getDestinationPort()
                );
        TcpIoLoop tcpIoLoop = this.tcpIoLoops.get(tcpIoLoopKey);
        if (tcpIoLoop == null) {
            if (inputTcpHeader.isFin()) {
                Log.d(TcpIoLoopFlowProcessor.class.getName(),
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
                Log.d(TcpIoLoopFlowProcessor.class.getName(),
                        "RECEIVE [RST ACK(LOOP NOT EXIST)], just ignore, tcp header =" + inputTcpHeader +
                                ", tcp loop = " + tcpIoLoop);
                return;
            }
            //Ignore ack on loop not exist
            Log.d(TcpIoLoopFlowProcessor.class.getName(),
                    "RECEIVE [ACK(LOOP NOT EXIST)], just ignore, tcp header =" + inputTcpHeader +
                            ", tcp loop = " + tcpIoLoop);
            return;
        }
        if (inputTcpHeader.isFin()) {
            //Confirm the ack is not for FIN first.
            if (TcpIoLoopStatus.ESTABLISHED == tcpIoLoop.getStatus()) {
                Log.d(TcpIoLoopFlowProcessor.class.getName(),
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
                delayDestroyTcpIoLoop(tcpIoLoop);
                return;
            }
            if (TcpIoLoopStatus.FIN_WAITE2 == tcpIoLoop.getStatus()) {
                Log.d(TcpIoLoopFlowProcessor.class.getName(),
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
            Log.d(TcpIoLoopFlowProcessor.class.getName(),
                    "RECEIVE [FIN ACK on " + tcpIoLoop.getStatus() + "], ILLEGAL STATUS just ignore, data size = " +
                            data.length +
                            ", tcp header =" + inputTcpHeader +
                            ", tcp loop = " + tcpIoLoop);
            return;
        }
        if (TcpIoLoopStatus.SYN_RECEIVED == tcpIoLoop.getStatus()) {
            //Receive the ack of syn_ack.
            tcpIoLoop.setStatus(TcpIoLoopStatus.ESTABLISHED);
            Log.d(TcpIoLoopFlowProcessor.class.getName(),
                    "RECEIVE [ACK FOR SYN_ACK], switch tcp loop to ESTABLISHED, tcp header =" + inputTcpHeader +
                            ", tcp loop = " + tcpIoLoop);
            return;
        }
        if (TcpIoLoopStatus.ESTABLISHED == tcpIoLoop.getStatus()) {
            if (data.length == 0) {
                //A ack for previous remote data
                Log.d(TcpIoLoopFlowProcessor.class.getName(),
                        "RECEIVE [ACK, WITHOUT DATA(" + (inputTcpHeader.isPsh() ? "PSH , " : "") +
                                "status=ESTABLISHED, size=0)], tcp header =" +
                                inputTcpHeader +
                                ", tcp loop = " + tcpIoLoop);
                return;
            }
            AgentMessageBody agentMessageBody = new AgentMessageBody(
                    MessageSerializer.INSTANCE.generateUuid(),
                    "DEFAULT_USER_TOKEN", tcpIoLoop.getDestinationAddress().getHostAddress(),
                    inputTcpHeader.getDestinationPort(),
                    AgentMessageBodyType.TCP_DATA,
                    data);
            AgentMessage agentMessage = new AgentMessage(
                    MessageSerializer.INSTANCE.generateUuidInBytes(),
                    EncryptionType.choose(),
                    agentMessageBody);
            tcpIoLoop.increaseAccumulateRemoteToDeviceAcknowledgementNumber(data.length);
            tcpIoLoop.getRemoteChannel().writeAndFlush(agentMessage);
            Log.d(TcpIoLoopFlowProcessor.class.getName(),
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
            Log.d(TcpIoLoopFlowProcessor.class.getName(),
                    "RECEIVE [ACK on CLOSE_WAIT, WITHOUT DATA (" + (inputTcpHeader.isPsh() ? "PSH , " : "") +
                            "status=ESTABLISHED, size=0)], tcp header =" +
                            inputTcpHeader +
                            ", tcp loop = " + tcpIoLoop);
            tcpIoLoop.getRemoteChannel().close().addListener(future -> {
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
            Log.d(TcpIoLoopFlowProcessor.class.getName(),
                    "RECEIVE [ACK on LAST_ACK], close tcp loop, tcp header ="
                            + inputTcpHeader + " tcp loop = " + tcpIoLoop);
            tcpIoLoop.increaseAccumulateRemoteToDeviceSequenceNumber(1);
            delayDestroyTcpIoLoop(tcpIoLoop);
            return;
        }
        if (TcpIoLoopStatus.FIN_WAITE1 == tcpIoLoop.getStatus()) {
            Log.d(TcpIoLoopFlowProcessor.class.getName(),
                    "RECEIVE [ACK on FIN_WAITE1], switch tcp loop status to FIN_WAITE2, tcp header ="
                            + inputTcpHeader + " tcp loop = " + tcpIoLoop);
            tcpIoLoop.increaseAccumulateRemoteToDeviceSequenceNumber(1);
            tcpIoLoop.setStatus(TcpIoLoopStatus.FIN_WAITE2);
            return;
        }
        Log.e(TcpIoLoopFlowProcessor.class.getName(),
                "RECEIVE [ACK(status=" + tcpIoLoop.getStatus() + ", size=" + (data == null ? 0 : data.length) +
                        ")], Tcp loop in ILLEGAL STATUS, ignore current packet do nothing just ack, tcp header ="
                        + inputTcpHeader + " tcp loop = " + tcpIoLoop);
    }

    private void delayDestroyTcpIoLoop(TcpIoLoop tcpIoLoop) {
        tcpIoLoop.destroy();
    }

    private void doRst(IpPacket inputIpPacket) {
        IpV4Header inputIpV4Header = (IpV4Header) inputIpPacket.getHeader();
        TcpPacket inputTcpPacket = (TcpPacket) inputIpPacket.getData();
        TcpHeader inputTcpHeader = inputTcpPacket.getHeader();
        final String tcpIoLoopKey =
                this.generateLoopKey(inputIpV4Header.getSourceAddress(), inputTcpHeader.getSourcePort()
                        , inputIpV4Header.getDestinationAddress(), inputTcpHeader.getDestinationPort()
                );
        TcpIoLoop tcpIoLoop = this.tcpIoLoops.get(tcpIoLoopKey);
        if (tcpIoLoop == null) {
            Log.d(TcpIoLoopFlowProcessor.class.getName(),
                    "RECEIVE [RST], destroy tcp loop(NOT EXIST), ip packet =" +
                            inputIpPacket +
                            ", tcp loop key = '" + tcpIoLoopKey + "'");
            return;
        }
        Log.d(TcpIoLoopFlowProcessor.class.getName(),
                "RECEIVE [RST], destroy tcp loop, ip packet =" +
                        inputIpPacket +
                        ", tcp loop = " + tcpIoLoop);
        delayDestroyTcpIoLoop(tcpIoLoop);
    }

    private void doFin(IpPacket inputIpPacket) {
        IpV4Header inputIpV4Header = (IpV4Header) inputIpPacket.getHeader();
        TcpPacket inputTcpPacket = (TcpPacket) inputIpPacket.getData();
        TcpHeader inputTcpHeader = inputTcpPacket.getHeader();
        final String tcpIoLoopKey =
                this.generateLoopKey(inputIpV4Header.getSourceAddress(), inputTcpHeader.getSourcePort()
                        , inputIpV4Header.getDestinationAddress(), inputTcpHeader.getDestinationPort()
                );
        TcpIoLoop tcpIoLoop = this.tcpIoLoops.get(tcpIoLoopKey);
        if (tcpIoLoop == null) {
            Log.d(TcpIoLoopFlowProcessor.class.getName(),
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
            Log.d(TcpIoLoopFlowProcessor.class.getName(),
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
            Log.d(TcpIoLoopFlowProcessor.class.getName(),
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
            delayDestroyTcpIoLoop(tcpIoLoop);
            return;
        }
        Log.e(TcpIoLoopFlowProcessor.class.getName(),
                "RECEIVE [FIN(status=" + tcpIoLoop.getStatus() +
                        ")], Tcp loop in ILLEGAL STATUS, ignore current packet do nothing, tcp header ="
                        + inputTcpHeader + " tcp loop = " + tcpIoLoop);
    }
}
