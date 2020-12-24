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
import org.jetbrains.annotations.Nullable;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.ppaass.agent.android.io.process.tcp.ITcpIoLoopConstant.TCP_IO_LOOP_KEY_FORMAT;
import static com.ppaass.agent.android.io.process.tcp.ITcpIoLoopConstant.TCP_LOOP;

public class TcpIoLoopFlowProcessor {
    private static final int DEFAULT_WINDOW_SIZE_IN_BYTE = 65535;
    private static final int DEFAULT_MSS_IN_BYTE = 256;
    private static final int DEFAULT_REMOTE_DATA_FRAME_LENGTH_IN_BYTE = 4096;
    private static final int BASE_SEQUENCE = (int) (Math.random() * 100000);
    private final VpnService vpnService;
    private final byte[] agentPrivateKeyBytes;
    private final byte[] proxyPublicKeyBytes;
    private final Bootstrap remoteBootstrap;
    private final ConcurrentMap<String, TcpIoLoop> tcpIoLoops;
    private final OutputStream remoteToDeviceStream;
    private final ExecutorService processorThreadPool;

    public TcpIoLoopFlowProcessor(VpnService vpnService, byte[] agentPrivateKeyBytes, byte[] proxyPublicKeyBytes,
                                  OutputStream remoteToDeviceStream) {
        this.vpnService = vpnService;
        this.agentPrivateKeyBytes = agentPrivateKeyBytes;
        this.proxyPublicKeyBytes = proxyPublicKeyBytes;
        this.remoteToDeviceStream = remoteToDeviceStream;
        this.remoteBootstrap = this.createRemoteBootstrap();
        this.tcpIoLoops = new ConcurrentHashMap<>();
        this.processorThreadPool = Executors.newFixedThreadPool(20);
    }

    public void process(IpPacket ipPacket) {
        TcpIoLoop tcpIoLoop = computeIoLoop(ipPacket);
        if (tcpIoLoop == null) {
            Log.e(TcpIoLoopFlowProcessor.class.getName(),
                    "Some problem happen can not process ip packet, ip packet = " + ipPacket);
            return;
        }
        Log.v(TcpIoLoopFlowProcessor.class.getName(),
                "Execute tcp flow for tcp loop, tcp loop = " + tcpIoLoop + ", ip packet = " + ipPacket);
        this.executeFlow(tcpIoLoop, ipPacket);
    }

    @Nullable
    private TcpIoLoop computeIoLoop(IpPacket ipPacket) {
        IpV4Header ipV4Header = (IpV4Header) ipPacket.getHeader();
        TcpPacket tcpPacket = (TcpPacket) ipPacket.getData();
        final InetAddress sourceAddress;
        try {
            sourceAddress = InetAddress.getByAddress(ipV4Header.getSourceAddress());
        } catch (UnknownHostException e) {
            Log.e(TcpIoLoopFlowProcessor.class.getName(),
                    "Fail to process ip packet because of source address is a unknown host, ip packet = " + ipPacket,
                    e);
            return null;
        }
        final int sourcePort = tcpPacket.getHeader().getSourcePort();
        final InetAddress destinationAddress;
        try {
            destinationAddress = InetAddress.getByAddress(ipV4Header.getDestinationAddress());
        } catch (UnknownHostException e) {
            Log.e(TcpIoLoopFlowProcessor.class.getName(),
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
                    TcpIoLoop result = new TcpIoLoop(key, sourceAddress, destinationAddress, sourcePort,
                            destinationPort);
                    result.setStatus(TcpIoLoopStatus.LISTEN);
                    return result;
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

    private void executeFlow(TcpIoLoop tcpIoLoop, IpPacket inputIpPacket) {
        TcpPacket inputTcpPacket = (TcpPacket) inputIpPacket.getData();
        TcpHeader inputTcpHeader = inputTcpPacket.getHeader();
        if (inputTcpHeader.isSyn()) {
            doSyn(tcpIoLoop, inputTcpHeader);
            return;
        }
        if (inputTcpHeader.isAck()) {
            byte[] inputData = inputTcpPacket.getData();
            if (inputData != null && inputData.length == 0) {
                inputData = null;
            }
            doAck(tcpIoLoop, inputTcpHeader, inputData);
            return;
        }
        if (inputTcpHeader.isRst()) {
            doRst(tcpIoLoop, inputTcpHeader);
            return;
        }
    }

    private void doSyn(TcpIoLoop tcpIoLoop, TcpHeader inputTcpHeader) {
        if (TcpIoLoopStatus.LISTEN != tcpIoLoop.getStatus()) {
            Log.e(TcpIoLoopFlowProcessor.class.getName(),
                    "Tcp loop is NOT in LISTEN status, return RST back to device, tcp header = " + inputTcpHeader +
                            ", tcp loop = " + tcpIoLoop);
            TcpIoLoopOutputWriter.INSTANCE.writeRst(tcpIoLoop, this.remoteToDeviceStream);
            this.tcpIoLoops.remove(tcpIoLoop.getKey());
            tcpIoLoop.destroy();
            return;
        }
        Log.v(TcpIoLoopFlowProcessor.class.getName(),
                "RECEIVE [SYN], initializing connection, tcp header = " + inputTcpHeader +
                        ", tcp loop = " + tcpIoLoop);
        this.remoteBootstrap
                .connect(tcpIoLoop.getDestinationAddress(), tcpIoLoop.getDestinationPort()).addListener(
                (ChannelFutureListener) connectResultFuture -> {
                    if (!connectResultFuture.isSuccess()) {
                        Log.e(TcpIoLoop.class.getName(),
                                "RECEIVE [SYN], initialize connection FAIL send RST back to device, tcp header ="
                                        + inputTcpHeader + " tcp loop = " + tcpIoLoop);
                        TcpIoLoopOutputWriter.INSTANCE.writeRst(tcpIoLoop, this.remoteToDeviceStream);
                        this.tcpIoLoops.remove(tcpIoLoop.getKey());
                        tcpIoLoop.destroy();
                        return;
                    }
                    tcpIoLoop.setRemoteChannel(connectResultFuture.channel());
                    tcpIoLoop.getRemoteChannel().attr(TCP_LOOP).setIfAbsent(tcpIoLoop);
                    TcpHeaderOption mssOption = null;
                    for (TcpHeaderOption option : inputTcpHeader.getOptions()) {
                        if (option.getKind() == TcpHeaderOption.Kind.MSS) {
                            mssOption = option;
                            break;
                        }
                    }
                    if (mssOption != null) {
                        ByteBuf mssOptionBuf = Unpooled.wrappedBuffer(mssOption.getInfo());
                        //this.mss = mssOptionBuf.readUnsignedShort();
                        tcpIoLoop.setMss(DEFAULT_MSS_IN_BYTE);
                    }
                    tcpIoLoop.setWindow(inputTcpHeader.getWindow());
                    tcpIoLoop.setStatus(TcpIoLoopStatus.SYN_RECEIVED);
                    tcpIoLoop.setCurrentRemoteToDeviceSeq(BASE_SEQUENCE);
                    tcpIoLoop.setCurrentRemoteToDeviceAck(inputTcpHeader.getSequenceNumber() + 1);
                    Log.d(TcpIoLoopFlowProcessor.class.getName(),
                            "RECEIVE [SYN], initializing connection success, switch tcp loop to SYN_RECIVED, tcp header = " +
                                    inputTcpHeader +
                                    ", tcp loop = " + tcpIoLoop);
                    TcpIoLoopOutputWriter.INSTANCE.writeSynAck(tcpIoLoop, remoteToDeviceStream);
                });
    }

    private void doAck(TcpIoLoop tcpIoLoop, TcpHeader inputTcpHeader, byte[] data) {
        if (TcpIoLoopStatus.SYN_RECEIVED == tcpIoLoop.getStatus()) {
            //Should receive the ack of syn_ack.
            tcpIoLoop.setStatus(TcpIoLoopStatus.ESTABLISHED);
            Log.d(TcpIoLoopFlowProcessor.class.getName(),
                    "RECEIVE [ACK], switch tcp loop to ESTABLISHED, tcp header =" + inputTcpHeader +
                            ", tcp loop = " + tcpIoLoop);
            return;
        }
        if (TcpIoLoopStatus.ESTABLISHED == tcpIoLoop.getStatus()) {
            if (inputTcpHeader.isPsh()) {
                //Psh ack
                tcpIoLoop.setCurrentRemoteToDeviceSeq(inputTcpHeader.getAcknowledgementNumber());
                if (data == null) {
                    tcpIoLoop.setCurrentRemoteToDeviceAck(inputTcpHeader.getSequenceNumber());
                    Log.d(TcpIoLoop.class.getName(),
                            "RECEIVE [PSH ACK WITHOUT DATA(size=0)], write data to remote, tcp header =" +
                                    inputTcpHeader +
                                    ", tcp loop = " + tcpIoLoop);
                    TcpIoLoopOutputWriter.INSTANCE.writeAck(tcpIoLoop, null, this.remoteToDeviceStream);
                    return;
                }
                tcpIoLoop.setCurrentRemoteToDeviceAck(inputTcpHeader.getSequenceNumber() + data.length);
                ByteBuf pshDataByteBuf = Unpooled.wrappedBuffer(data);
                Log.d(TcpIoLoop.class.getName(),
                        "RECEIVE [PSH ACK WITH DATA(size=" + data.length + ")], write data to remote, tcp header =" +
                                inputTcpHeader +
                                ", tcp loop = " + tcpIoLoop + ", DATA: \n" + ByteBufUtil.prettyHexDump(pshDataByteBuf));
//                TcpIoLoopOutputWriter.INSTANCE.writeAck(tcpIoLoop, null, this.remoteToDeviceStream);
                tcpIoLoop.getRemoteChannel().writeAndFlush(pshDataByteBuf);
                return;
            }
            if (inputTcpHeader.isFin()) {
                if (TcpIoLoopStatus.FIN_WAITE2 == tcpIoLoop.getStatus()) {
                    tcpIoLoop.setCurrentRemoteToDeviceAck(inputTcpHeader.getSequenceNumber() + 1);
                    tcpIoLoop.setStatus(TcpIoLoopStatus.TIME_WAITE);
                    Log.d(TcpIoLoop.class.getName(),
                            "RECEIVE [ACK(status=FIN_WAITE2)], switch tcp loop status to TIME_WAITE, send ack, tcp header ="
                                    + inputTcpHeader + " tcp loop = " + tcpIoLoop);
                    TcpIoLoopOutputWriter.INSTANCE.writeAck(tcpIoLoop, null, this.remoteToDeviceStream);
                    this.tcpIoLoops.remove(tcpIoLoop.getKey());
                    tcpIoLoop.destroy();
                    return;
                }
            }
            tcpIoLoop.setCurrentRemoteToDeviceSeq(inputTcpHeader.getAcknowledgementNumber());
            if (data == null) {
                tcpIoLoop.setCurrentRemoteToDeviceAck(inputTcpHeader.getSequenceNumber());
                Log.d(TcpIoLoop.class.getName(),
                        "RECEIVE [ACK WITHOUT DATA(status=ESTABLISHED, size=0)], write data to remote, tcp header =" +
                                inputTcpHeader +
                                ", tcp loop = " + tcpIoLoop);
                TcpIoLoopOutputWriter.INSTANCE.writeAck(tcpIoLoop, null, this.remoteToDeviceStream);
                return;
            }
            tcpIoLoop.setCurrentRemoteToDeviceAck(inputTcpHeader.getSequenceNumber() + data.length);
            ByteBuf pshDataByteBuf = Unpooled.wrappedBuffer(data);
            Log.d(TcpIoLoop.class.getName(),
                    "RECEIVE [ACK WITH DATA(status=ESTABLISHED, size=" + data.length +
                            ")], write data to remote, tcp header =" +
                            inputTcpHeader +
                            ", tcp loop = " + tcpIoLoop + ", DATA: \n" + ByteBufUtil.prettyHexDump(pshDataByteBuf));
//            TcpIoLoopOutputWriter.INSTANCE.writeAck(tcpIoLoop, null, this.remoteToDeviceStream);
            tcpIoLoop.getRemoteChannel().writeAndFlush(pshDataByteBuf);
            return;
        }
        if (TcpIoLoopStatus.FIN_WAITE1 == tcpIoLoop.getStatus()) {
            Log.d(TcpIoLoop.class.getName(),
                    "RECEIVE [ACK(status=FIN_WAITE1)], switch tcp loop status to FIN_WAITE2, tcp header ="
                            + inputTcpHeader + " tcp loop = " + tcpIoLoop);
            tcpIoLoop.setStatus(TcpIoLoopStatus.FIN_WAITE2);
            return;
        }
        if (TcpIoLoopStatus.LAST_ACK == tcpIoLoop.getStatus()) {
            tcpIoLoop.setStatus(TcpIoLoopStatus.CLOSED);
            Log.d(TcpIoLoop.class.getName(),
                    "RECEIVE [ACK(status=LAST_ACK)], close tcp loop, tcp header ="
                            + inputTcpHeader + " tcp loop = " + tcpIoLoop);
            this.tcpIoLoops.remove(tcpIoLoop.getKey());
            tcpIoLoop.destroy();
            return;
        }
        Log.e(TcpIoLoop.class.getName(),
                "Tcp loop in illegal state, send RST back to device, tcp header ="
                        + inputTcpHeader + " tcp loop = " + tcpIoLoop);
        this.tcpIoLoops.remove(tcpIoLoop.getKey());
        tcpIoLoop.destroy();
    }

    private void doRst(TcpIoLoop tcpIoLoop, TcpHeader inputTcpHeader) {
        Log.d(TcpIoLoop.class.getName(),
                "RECEIVE [RST], destroy tcp loop, tcp header =" +
                        inputTcpHeader +
                        ", tcp loop = " + tcpIoLoop);
        this.tcpIoLoops.remove(tcpIoLoop.getKey());
        tcpIoLoop.destroy();
    }

    private void doFin(TcpIoLoop tcpIoLoop, TcpHeader inputTcpHeader) {
        tcpIoLoop.setStatus(TcpIoLoopStatus.CLOSE_WAIT);
        tcpIoLoop.setCurrentRemoteToDeviceAck(inputTcpHeader.getSequenceNumber() + 1);
        Log.d(TcpIoLoop.class.getName(),
                "RECEIVE [FIN], switch tcp loop status to CLOSE_WAIT, tcp header =" +
                        inputTcpHeader +
                        ", tcp loop = " + tcpIoLoop);
        TcpIoLoopOutputWriter.INSTANCE.writeAck(tcpIoLoop, null, this.remoteToDeviceStream);
        tcpIoLoop.setStatus(TcpIoLoopStatus.LAST_ACK);
        Log.d(TcpIoLoop.class.getName(),
                "RECEIVE [FIN], switch tcp loop status to LAST_ACK, tcp header =" +
                        inputTcpHeader +
                        ", tcp loop = " + tcpIoLoop);
        TcpIoLoopOutputWriter.INSTANCE.writeFin(tcpIoLoop, this.remoteToDeviceStream);
    }
}
