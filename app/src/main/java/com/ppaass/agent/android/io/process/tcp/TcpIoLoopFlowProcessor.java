package com.ppaass.agent.android.io.process.tcp;

import android.net.VpnService;
import android.util.Log;
import com.ppaass.agent.android.io.process.common.VpnNioSocketChannel;
import com.ppaass.agent.android.io.protocol.ip.IpPacket;
import com.ppaass.agent.android.io.protocol.ip.IpV4Header;
import com.ppaass.agent.android.io.protocol.tcp.TcpHeader;
import com.ppaass.agent.android.io.protocol.tcp.TcpHeaderOption;
import com.ppaass.agent.android.io.protocol.tcp.TcpPacket;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.concurrent.*;

import static com.ppaass.agent.android.io.process.tcp.ITcpIoLoopConstant.TCP_IO_LOOP_KEY_FORMAT;
import static com.ppaass.agent.android.io.process.tcp.ITcpIoLoopConstant.TCP_LOOP;

public class TcpIoLoopFlowProcessor {
    private static final int DEFAULT_2MSL_TIME = 10;
    private static final int DEFAULT_DELAY_CLOSE_TIME = 60;
    private final Bootstrap remoteBootstrap;
    private final ConcurrentMap<String, TcpIoLoop> tcpIoLoops;
    private final OutputStream remoteToDeviceStream;
    private final ExecutorService tcpLoopResendTaskThreadPool;
    private final ScheduledExecutorService delayCloseTcpIoLoopThreadPool;

    private static class TcpLoopResendTask implements Runnable {
        private final TcpIoLoop tcpIoLoop;
        private final OutputStream remoteToDeviceStream;

        private TcpLoopResendTask(TcpIoLoop tcpIoLoop, OutputStream remoteToDeviceStream) {
            this.tcpIoLoop = tcpIoLoop;
            this.remoteToDeviceStream = remoteToDeviceStream;
        }

        @Override
        public void run() {
            while (this.tcpIoLoop.isAlive()) {
                synchronized (tcpIoLoop) {
                    this.tcpIoLoop.getTcpWindow().forEach((remoteSequence, ipPacketWrapper) -> {
                        if (System.currentTimeMillis() - ipPacketWrapper.getInsertTime() > 2000) {
                            Log.d(TcpIoLoopFlowProcessor.class.getName(),
                                    "RESEND TASK, tcp loop = " + this);
                            TcpIoLoopRemoteToDeviceWriter.INSTANCE
                                    .writeIpPacketToDevice(ipPacketWrapper.getIpPacket(), this.tcpIoLoop.getKey(),
                                            this.remoteToDeviceStream);
                        }
                    });
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Log.d(TcpIoLoopFlowProcessor.class.getName(),
                                "RESEND TASK, error happen because of exception, tcp loop = " + this, e);
                    }
                }
            }
        }
    }

    private static class WriteToRemoteListener implements ChannelFutureListener {
        private int retryTimes = 0;
        private final TcpIoLoop tcpIoLoop;
        private final TcpHeader inputTcpHeader;
        private final IpV4Header inputIpV4Header;
        private final byte[] dataWriteToRemote;
        private final OutputStream remoteToDeviceStream;

        private WriteToRemoteListener(TcpIoLoop tcpIoLoop,
                                      TcpHeader inputTcpHeader,
                                      IpV4Header inputIpV4Header, byte[] dataWriteToRemote,
                                      OutputStream remoteToDeviceStream) {
            this.tcpIoLoop = tcpIoLoop;
            this.inputTcpHeader = inputTcpHeader;
            this.inputIpV4Header = inputIpV4Header;
            this.dataWriteToRemote = dataWriteToRemote;
            this.remoteToDeviceStream = remoteToDeviceStream;
        }

        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            if (!future.isSuccess()) {
                if (retryTimes >= 3 || !tcpIoLoop.getRemoteChannel().isOpen()) {
                    Log.e(TcpIoLoopFlowProcessor.class.getName(),
                            "Fail to write request to remote, reset connection, tcp header =" +
                                    inputTcpHeader +
                                    ", tcp loop = " + tcpIoLoop);
                    IpPacket reset = TcpIoLoopRemoteToDeviceWriter.INSTANCE
                            .buildRst(inputIpV4Header.getDestinationAddress(),
                                    inputTcpHeader.getDestinationPort(),
                                    inputIpV4Header.getSourceAddress(), inputTcpHeader.getSourcePort(),
                                    inputTcpHeader.getAcknowledgementNumber(),
                                    inputTcpHeader.getSequenceNumber() + dataWriteToRemote.length);
                    TcpIoLoopRemoteToDeviceWriter.INSTANCE
                            .writeIpPacketToDevice(reset, this.tcpIoLoop.getKey(), remoteToDeviceStream);
                    tcpIoLoop.destroy();
                    return;
                }
                tcpIoLoop.getRemoteChannel().writeAndFlush(Unpooled.wrappedBuffer(dataWriteToRemote)).addListener(this);
                retryTimes++;
                return;
            }
            tcpIoLoop.getRemoteChannel().read();
        }
    }

    public TcpIoLoopFlowProcessor(VpnService vpnService, OutputStream remoteToDeviceStream) {
        this.remoteBootstrap = this.createRemoteBootstrap(vpnService, remoteToDeviceStream);
        this.tcpIoLoops = new ConcurrentHashMap<>();
        this.remoteToDeviceStream = remoteToDeviceStream;
        this.tcpLoopResendTaskThreadPool = Executors.newFixedThreadPool(32);
        this.delayCloseTcpIoLoopThreadPool = Executors.newScheduledThreadPool(32);
    }

    public void shutdown() {
        this.remoteBootstrap.config().group().shutdownGracefully();
    }

    private Bootstrap createRemoteBootstrap(VpnService vpnService, OutputStream remoteToDeviceStream) {
        Bootstrap remoteBootstrap = new Bootstrap();
        remoteBootstrap.group(new NioEventLoopGroup());
        remoteBootstrap.channelFactory(() -> new VpnNioSocketChannel(vpnService));
        remoteBootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000);
        remoteBootstrap.option(ChannelOption.SO_KEEPALIVE, false);
        remoteBootstrap.option(ChannelOption.AUTO_READ, false);
        remoteBootstrap.option(ChannelOption.AUTO_CLOSE, false);
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
                    TcpLoopResendTask tcpLoopResendTask = new TcpLoopResendTask(tcpIoLoop, this.remoteToDeviceStream);
                    this.tcpLoopResendTaskThreadPool.execute(tcpLoopResendTask);
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
        synchronized (this.tcpIoLoops) {
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
                TcpIoLoopRemoteToDeviceWriter.INSTANCE.writeIpPacketToDevice(ackPacket, tcpIoLoopKey,
                        this.remoteToDeviceStream);
                Log.e(TcpIoLoopFlowProcessor.class.getName(),
                        "Duplicate syn request coming, ack and ignore it, ip packet = " +
                                inputIpPacket + ", tcp loop key = " + tcpIoLoopKey);
                return;
            }
        }
        final InetAddress destinationAddress;
        try {
            destinationAddress = InetAddress.getByAddress(inputIpV4Header.getDestinationAddress());
        } catch (UnknownHostException e) {
            Log.e(TcpIoLoopFlowProcessor.class.getName(),
                    "Fail to parse destination address, ip packet = " +
                            inputIpPacket + ", tcp loop key = " + tcpIoLoopKey);
            return;
        }
        this.remoteBootstrap
                .connect(destinationAddress, inputTcpHeader.getDestinationPort())
                .addListener(
                        (ChannelFutureListener) connectResultFuture -> {
                            if (!connectResultFuture.isSuccess()) {
                                IpPacket ackPacket = TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildAck(
                                        inputIpV4Header.getDestinationAddress(),
                                        inputTcpHeader.getDestinationPort(),
                                        inputIpV4Header.getSourceAddress(),
                                        inputTcpHeader.getSourcePort(),
                                        inputTcpHeader.getAcknowledgementNumber(),
                                        inputTcpHeader.getSequenceNumber(),
                                        null
                                );
                                TcpIoLoopRemoteToDeviceWriter.INSTANCE.writeIpPacketToDevice(ackPacket, tcpIoLoopKey,
                                        this.remoteToDeviceStream);
                                Log.e(TcpIoLoopFlowProcessor.class.getName(),
                                        "RECEIVE [SYN], initialize connection FAIL, ack and ignore the packet (1), tcp header ="
                                                + inputTcpHeader + " tcp loop key = " + tcpIoLoopKey);
                                return;
                            }
                            TcpIoLoop tcpIoLoop = getOrCreateTcpIoLoop(inputIpPacket, connectResultFuture.channel());
                            if (tcpIoLoop == null) {
                                IpPacket ackPacket = TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildAck(
                                        inputIpV4Header.getDestinationAddress(),
                                        inputTcpHeader.getDestinationPort(),
                                        inputIpV4Header.getSourceAddress(),
                                        inputTcpHeader.getSourcePort(),
                                        inputTcpHeader.getAcknowledgementNumber(),
                                        inputTcpHeader.getSequenceNumber(),
                                        null
                                );
                                TcpIoLoopRemoteToDeviceWriter.INSTANCE.writeIpPacketToDevice(ackPacket, tcpIoLoopKey,
                                        this.remoteToDeviceStream);
                                Log.e(TcpIoLoopFlowProcessor.class.getName(),
                                        "RECEIVE [SYN], initialize connection FAIL, ack and ignore the packet (2), tcp header ="
                                                + inputTcpHeader + " tcp loop key = " + tcpIoLoopKey);
                                return;
                            }
                            tcpIoLoop.setAccumulateRemoteToDeviceAcknowledgementNumber(
                                    inputTcpHeader.getSequenceNumber());
                            tcpIoLoop.increaseAccumulateRemoteToDeviceAcknowledgementNumber(1);
                            tcpIoLoop.getRemoteChannel().attr(TCP_LOOP).setIfAbsent(tcpIoLoop);
                            IpPacket synAck = TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildSynAck(
                                    inputIpV4Header.getDestinationAddress(),
                                    tcpIoLoop.getDestinationPort(),
                                    inputIpV4Header.getSourceAddress(),
                                    tcpIoLoop.getSourcePort(),
                                    tcpIoLoop.getAccumulateRemoteToDeviceSequenceNumber(),
                                    tcpIoLoop.getAccumulateRemoteToDeviceAcknowledgementNumber(),
                                    tcpIoLoop.getMss()
                            );
                            TcpIoLoopRemoteToDeviceWriter.INSTANCE
                                    .writeIpPacketToDevice(synAck, tcpIoLoop.getKey(),
                                            this.remoteToDeviceStream);
                            tcpIoLoop.increaseAccumulateRemoteToDeviceSequenceNumber(1);
                            tcpIoLoop.setStatus(TcpIoLoopStatus.SYN_RECEIVED);
                            Log.d(TcpIoLoopFlowProcessor.class.getName(),
                                    "RECEIVE [SYN], initializing connection SUCCESS, switch tcp loop to SYN_RECIVED, tcp header = " +
                                            inputTcpHeader +
                                            ", tcp loop = " + tcpIoLoop);
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
                        .writeIpPacketToDevice(finAck, tcpIoLoopKey, this.remoteToDeviceStream);
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
                        "RECEIVE [FIN ACK on ESTABLISHED], mark status to CLOSE_WAIT, do resend data size = " +
                                data.length + ", tcp header =" + inputTcpHeader +
                                ", tcp loop = " + tcpIoLoop);
                tcpIoLoop.setStatus(TcpIoLoopStatus.CLOSE_WAIT);
                tcpIoLoop.increaseAccumulateRemoteToDeviceAcknowledgementNumber(1);
                IpPacket ackForFinAck = TcpIoLoopRemoteToDeviceWriter.INSTANCE
                        .buildAck(inputIpV4Header.getDestinationAddress(), inputTcpHeader.getDestinationPort(),
                                inputIpV4Header.getSourceAddress(), inputTcpHeader.getSourcePort(),
                                tcpIoLoop.getAccumulateRemoteToDeviceSequenceNumber(),
                                tcpIoLoop.getAccumulateRemoteToDeviceAcknowledgementNumber(),
                                null);
                TcpIoLoopRemoteToDeviceWriter.INSTANCE
                        .writeIpPacketToDevice(ackForFinAck, tcpIoLoopKey, this.remoteToDeviceStream);
                synchronized (tcpIoLoop) {
                    tcpIoLoop.getTcpWindow().forEach((remoteSequence, ipPacketWrapper) -> {
                        Log.d(TcpIoLoopFlowProcessor.class.getName(),
                                "RESEND WINDOW FOR FIN, tcp loop = " + this);
                        TcpIoLoopRemoteToDeviceWriter.INSTANCE
                                .writeIpPacketToDevice(ipPacketWrapper.getIpPacket(), tcpIoLoop.getKey(),
                                        this.remoteToDeviceStream);
                    });
                }
                this.delayCloseTcpIoLoopThreadPool.schedule(tcpIoLoop::destroy, DEFAULT_DELAY_CLOSE_TIME, TimeUnit.SECONDS);
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
                        .writeIpPacketToDevice(ackForFinAck, tcpIoLoopKey, this.remoteToDeviceStream);
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
                long confirmRemoteSequence = inputTcpHeader.getAcknowledgementNumber();
                synchronized (tcpIoLoop) {
                    tcpIoLoop.getTcpWindow().forEach((remoteSequence, dataLength) -> {
                        if (remoteSequence <= confirmRemoteSequence) {
                            Log.d(TcpIoLoopFlowProcessor.class.getName(),
                                    "CONFIRM SEQUENCE (" + remoteSequence + "), tcp header =" +
                                            inputTcpHeader +
                                            ", tcp loop = " + tcpIoLoop);
                            tcpIoLoop.getTcpWindow().remove(remoteSequence);
                        }
                    });
                    if (tcpIoLoop.getTcpWindow().isEmpty()) {
                        Log.d(TcpIoLoopFlowProcessor.class.getName(),
                                "ALL REMOTE SEQUENCE CONFIRMED, read from remote, tcp header =" +
                                        inputTcpHeader +
                                        ", tcp loop = " + tcpIoLoop);
                        tcpIoLoop.getRemoteChannel().read();
                        return;
                    }
                }
                return;
            }
            ByteBuf dataByteBuf = Unpooled.wrappedBuffer(data);
            Log.d(TcpIoLoopFlowProcessor.class.getName(),
                    "RECEIVE [ACK WITH DATA(" + (inputTcpHeader.isPsh() ? "PSH , " : "") + "status=ESTABLISHED, size=" +
                            data.length +
                            ")], write data to remote, tcp header =" +
                            inputTcpHeader +
                            ", tcp loop = " + tcpIoLoop + ", DATA: \n" + ByteBufUtil.prettyHexDump(dataByteBuf));
            tcpIoLoop.increaseAccumulateRemoteToDeviceAcknowledgementNumber(data.length);
            byte[] dataMaybeResend = ByteBufUtil.getBytes(dataByteBuf);
            tcpIoLoop.getRemoteChannel().writeAndFlush(dataByteBuf).addListener(
                    new WriteToRemoteListener(tcpIoLoop, inputTcpHeader, inputIpV4Header, dataMaybeResend,
                            remoteToDeviceStream));
            return;
        }
        if (TcpIoLoopStatus.CLOSE_WAIT == tcpIoLoop.getStatus()) {
            //A ack for previous remote data
            Log.d(TcpIoLoopFlowProcessor.class.getName(),
                    "RECEIVE [ACK on CLOSE_WAIT, WITHOUT DATA (" + (inputTcpHeader.isPsh() ? "PSH , " : "") +
                            "status=ESTABLISHED, size=0)], tcp header =" +
                            inputTcpHeader +
                            ", tcp loop = " + tcpIoLoop);
            long confirmRemoteSequence = inputTcpHeader.getAcknowledgementNumber();
            synchronized (tcpIoLoop) {
                tcpIoLoop.getTcpWindow().forEach((remoteSequence, dataLength) -> {
                    if (remoteSequence <= confirmRemoteSequence) {
                        Log.d(TcpIoLoopFlowProcessor.class.getName(),
                                "CONFIRM SEQUENCE on CLOSE_WAIT (" + remoteSequence + "), tcp header =" +
                                        inputTcpHeader +
                                        ", tcp loop = " + tcpIoLoop);
                        tcpIoLoop.getTcpWindow().remove(remoteSequence);
                    }
                });
                if (tcpIoLoop.getTcpWindow().isEmpty()) {
                    Log.d(TcpIoLoopFlowProcessor.class.getName(),
                            "ALL REMOTE SEQUENCE CONFIRMED on CLOSE_WAIT, read from remote, tcp header =" +
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
                                .writeIpPacketToDevice(finAck, tcpIoLoopKey, this.remoteToDeviceStream);
                        tcpIoLoop.setStatus(TcpIoLoopStatus.LAST_ACK);
                    });
                    return;
                }
            }
            return;
        }
        if (TcpIoLoopStatus.LAST_ACK == tcpIoLoop.getStatus()) {
            Log.d(TcpIoLoopFlowProcessor.class.getName(),
                    "RECEIVE [ACK on LAST_ACK], close tcp loop, tcp header ="
                            + inputTcpHeader + " tcp loop = " + tcpIoLoop);
            tcpIoLoop.increaseAccumulateRemoteToDeviceSequenceNumber(1);
            tcpIoLoop.destroy();
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

    private void doRst(IpPacket inputIpPacket) {
        IpV4Header inputIpV4Header = (IpV4Header) inputIpPacket.getHeader();
        TcpPacket inputTcpPacket = (TcpPacket) inputIpPacket.getData();
        TcpHeader inputTcpHeader = inputTcpPacket.getHeader();
        final String tcpIoLoopKey =
                this.generateLoopKey(inputIpV4Header.getSourceAddress(), inputTcpHeader.getSourcePort()
                        , inputIpV4Header.getDestinationAddress(), inputTcpHeader.getDestinationPort()
                );
        TcpIoLoop tcpIoLoop;
        synchronized (this.tcpIoLoops) {
            tcpIoLoop = this.tcpIoLoops.get(tcpIoLoopKey);
            if (tcpIoLoop == null) {
                Log.d(TcpIoLoopFlowProcessor.class.getName(),
                        "RECEIVE [RST], destroy tcp loop(NOT EXIST), ip packet =" +
                                inputIpPacket +
                                ", tcp loop key = '" + tcpIoLoopKey + "'");
                return;
            }
        }
        Log.d(TcpIoLoopFlowProcessor.class.getName(),
                "RECEIVE [RST], destroy tcp loop, ip packet =" +
                        inputIpPacket +
                        ", tcp loop = " + tcpIoLoop);
        tcpIoLoop.getRemoteChannel().close();
        tcpIoLoop.destroy();
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
                    .writeIpPacketToDevice(finAck1, tcpIoLoopKey, this.remoteToDeviceStream);
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
                    .writeIpPacketToDevice(finAck2, tcpIoLoopKey, this.remoteToDeviceStream);
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
                    .writeIpPacketToDevice(finAck1, tcpIoLoop.getKey(),
                            this.remoteToDeviceStream);
            synchronized (tcpIoLoop) {
                tcpIoLoop.getTcpWindow().forEach((remoteSequence, ipPacketWrapper) -> {
                    Log.d(TcpIoLoopFlowProcessor.class.getName(),
                            "RECEIVE [FIN(status=ESTABLISHED, STEP2)], RESEND WINDOW FOR FIN, tcp loop = " + this);
                    TcpIoLoopRemoteToDeviceWriter.INSTANCE
                            .writeIpPacketToDevice(ipPacketWrapper.getIpPacket(), tcpIoLoop.getKey(),
                                    this.remoteToDeviceStream);
                });
            }
            this.delayCloseTcpIoLoopThreadPool.schedule(tcpIoLoop::destroy, DEFAULT_DELAY_CLOSE_TIME, TimeUnit.SECONDS);
            return;
        }
        Log.e(TcpIoLoopFlowProcessor.class.getName(),
                "RECEIVE [FIN(status=" + tcpIoLoop.getStatus() +
                        ")], Tcp loop in ILLEGAL STATUS, ignore current packet do nothing, tcp header ="
                        + inputTcpHeader + " tcp loop = " + tcpIoLoop);
    }
}
