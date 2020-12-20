package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import com.ppaass.agent.android.io.process.IIoLoop;
import com.ppaass.agent.android.io.process.IoLoopHolder;
import com.ppaass.agent.android.io.protocol.ip.*;
import com.ppaass.agent.android.io.protocol.tcp.TcpHeader;
import com.ppaass.agent.android.io.protocol.tcp.TcpHeaderOption;
import com.ppaass.agent.android.io.protocol.tcp.TcpPacket;
import com.ppaass.agent.android.io.protocol.tcp.TcpPacketBuilder;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Random;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static com.ppaass.agent.android.io.process.IIoConstant.TCP_LOOP;

public class TcpIoLoop implements IIoLoop, Runnable {
    private static final int DEFAULT_WINDOW_SIZE_IN_BYTE = 65535;
    private final InetAddress sourceAddress;
    private final InetAddress destinationAddress;
    private final int sourcePort;
    private final int destinationPort;
    private final String key;
    private TcpIoLoopStatus status;
    private final Bootstrap targetTcpBootstrap;
    private final Random random = new Random();
    private Channel targetChannel;
    private final FileOutputStream vpnOutputStream;
    private long appToVpnSequenceNumber;
    private long appToVpnAcknowledgementNumber;
    private long vpnToAppSequenceNumber;
    private long vpnToAppAcknowledgementNumber;
    private int mss;
    private final Semaphore writeTargetDataSemaphore;
    private final BlockingDeque<IpPacket> ipPacketBlockingDeque;
    private boolean alive;
    private int window;

    public TcpIoLoop(InetAddress sourceAddress, InetAddress destinationAddress, int sourcePort, int destinationPort,
                     String key, Bootstrap targetTcpBootstrap, FileOutputStream vpnOutputStream) {
        this.sourceAddress = sourceAddress;
        this.destinationAddress = destinationAddress;
        this.sourcePort = sourcePort;
        this.destinationPort = destinationPort;
        this.key = key;
        this.targetTcpBootstrap = targetTcpBootstrap;
        this.vpnOutputStream = vpnOutputStream;
        this.status = TcpIoLoopStatus.LISTEN;
        this.mss = -1;
        this.window = -1;
        writeTargetDataSemaphore = new Semaphore(4);
        this.ipPacketBlockingDeque = new LinkedBlockingDeque<>();
        this.alive = true;
    }

    @Override
    public String getKey() {
        return this.key;
    }

    public void writeToApp(IpPacket ipPacket) {
        Log.d(TcpIoLoop.class.getName(), "WRITE TO APP, output ip packet" + ipPacket + ", tcp loop = " + this);
        try {
            this.vpnOutputStream.write(IpPacketWriter.INSTANCE.write(ipPacket));
            this.vpnOutputStream.flush();
        } catch (IOException e) {
            Log.e(TcpIoLoop.class.getName(), "Fail to write ip packet to app because of exception.", e);
        }
    }

    private IpPacket buildIpPacket(TcpPacketBuilder tcpPacketBuilder) {
        short identification = (short) Math.abs(random.nextInt());
        IpV4Header ipV4Header =
                new IpV4HeaderBuilder()
                        .destinationAddress(this.sourceAddress.getAddress())
                        .sourceAddress(this.destinationAddress.getAddress())
                        .protocol(IpDataProtocol.TCP).identification(identification).build();
        tcpPacketBuilder
                .sequenceNumber(this.vpnToAppSequenceNumber)
                .acknowledgementNumber(this.vpnToAppAcknowledgementNumber)
                .destinationPort(this.sourcePort)
                .sourcePort(this.destinationPort).window(DEFAULT_WINDOW_SIZE_IN_BYTE);
        IpPacketBuilder ipPacketBuilder = new IpPacketBuilder();
        TcpPacket tcpPacket = tcpPacketBuilder.build();
        ipPacketBuilder.data(tcpPacket);
        ipPacketBuilder.header(ipV4Header);
        return ipPacketBuilder.build();
    }

    private IpPacket buildSynAck() {
        TcpPacketBuilder synAckTcpPacketBuilder = new TcpPacketBuilder();
        synAckTcpPacketBuilder.ack(true).syn(true);
        return this.buildIpPacket(synAckTcpPacketBuilder);
    }

    public IpPacket buildAck(byte[] data) {
        TcpPacketBuilder ackTcpPacketBuilder = new TcpPacketBuilder();
        ackTcpPacketBuilder.ack(true);
        ackTcpPacketBuilder.data(data);
        return this.buildIpPacket(ackTcpPacketBuilder);
    }

    public IpPacket buildPshAck(byte[] data) {
        TcpPacketBuilder ackTcpPacketBuilder = new TcpPacketBuilder();
        ackTcpPacketBuilder.ack(true).psh(true);
        ackTcpPacketBuilder.data(data);
        return this.buildIpPacket(ackTcpPacketBuilder);
    }

    public IpPacket buildFinAck(byte[] data) {
        TcpPacketBuilder ackTcpPacketBuilder = new TcpPacketBuilder();
        ackTcpPacketBuilder.ack(true).fin(true);
        ackTcpPacketBuilder.data(data);
        return this.buildIpPacket(ackTcpPacketBuilder);
    }

    @Override
    public final void execute(IpPacket inputIpPacket) {
        this.ipPacketBlockingDeque.push(inputIpPacket);
    }

    public int getMss() {
        return mss;
    }

    @Override
    public void destroy() {
        this.alive = false;
        this.status = TcpIoLoopStatus.CLOSED;
        this.targetChannel = null;
        IoLoopHolder.INSTANCE.remove(this.getKey());
    }

    public synchronized long getAppToVpnSequenceNumber() {
        return appToVpnSequenceNumber;
    }

    public synchronized void setAppToVpnSequenceNumber(long appToVpnSequenceNumber) {
        this.appToVpnSequenceNumber = appToVpnSequenceNumber;
    }

    public synchronized long getAppToVpnAcknowledgementNumber() {
        return appToVpnAcknowledgementNumber;
    }

    public synchronized void setAppToVpnAcknowledgementNumber(long appToVpnAcknowledgementNumber) {
        this.appToVpnAcknowledgementNumber = appToVpnAcknowledgementNumber;
    }

    public synchronized long getVpnToAppSequenceNumber() {
        return vpnToAppSequenceNumber;
    }

    public synchronized void setVpnToAppSequenceNumber(long vpnToAppSequenceNumber) {
        this.vpnToAppSequenceNumber = vpnToAppSequenceNumber;
    }

    public synchronized long getVpnToAppAcknowledgementNumber() {
        return vpnToAppAcknowledgementNumber;
    }

    public synchronized void setVpnToAppAcknowledgementNumber(long vpnToAppAcknowledgementNumber) {
        this.vpnToAppAcknowledgementNumber = vpnToAppAcknowledgementNumber;
    }

    public InetAddress getSourceAddress() {
        return sourceAddress;
    }

    public InetAddress getDestinationAddress() {
        return destinationAddress;
    }

    public int getSourcePort() {
        return sourcePort;
    }

    public int getDestinationPort() {
        return destinationPort;
    }

    public Semaphore getWriteTargetDataSemaphore() {
        return writeTargetDataSemaphore;
    }

    public int getWindow() {
        return window;
    }

    @Override
    public void run() {
        while (this.alive) {
            final IpPacket inputIpPacket;
            try {
                long startTime = System.currentTimeMillis();
                Log.d(TcpIoLoop.class.getName(),
                        "Begin to take input ip packet for tcp loop[start time=" + startTime + "], tcp loop = " + this);
                inputIpPacket = this.ipPacketBlockingDeque.poll(20000L, TimeUnit.SECONDS);
                long endTime = System.currentTimeMillis();
                if (inputIpPacket == null) {
                    Log.d(TcpIoLoop.class.getName(),
                            "No ip packet for tcp loop in 20 seconds, skip and take again [end time=" + endTime + ", use=" +
                                    (endTime - startTime) / 1000 + "], tcp loop = " + this);
                    continue;
                }
                Log.d(TcpIoLoop.class.getName(),
                        "Success take input ip packet for tcp loop[end time=" + endTime + ", use=" +
                                (endTime - startTime) / 1000 + "], tcp loop = " + this);
            } catch (InterruptedException e) {
                Log.e(TcpIoLoop.class.getName(), "Fail to take ip packet from input queue because of exception.", e);
                this.destroy();
                return;
            }
            //Do some thing
            IIpHeader inputIpHeader = inputIpPacket.getHeader();
            if (inputIpHeader.getVersion() != IpHeaderVersion.V4) {
                Log.e(TcpIoLoop.class.getName(),
                        "Input ip package is not IPV4, ignore it, input ip packet = " + inputIpPacket +
                                ", tcp loop = " +
                                this);
                continue;
            }
            IpV4Header inputIpV4Header = (IpV4Header) inputIpHeader;
            if (inputIpV4Header.getProtocol() != IpDataProtocol.TCP) {
                Log.e(TcpIoLoop.class.getName(),
                        "Input ip package is not TCP, ignore it, input ip packet = " + inputIpPacket + ", tcp loop = " +
                                this);
                continue;
            }
            TcpPacket inputTcpPacket = (TcpPacket) inputIpPacket.getData();
            TcpHeader inputTcpHeader = inputTcpPacket.getHeader();
            this.appToVpnSequenceNumber = inputTcpHeader.getSequenceNumber();
            this.appToVpnAcknowledgementNumber = inputTcpHeader.getAcknowledgementNumber();
            if (inputTcpHeader.isSyn() && !inputTcpHeader.isAck()) {
                //Receive a syn.
                //First syn
                if (this.status == TcpIoLoopStatus.CLOSED) {
//                Log.e(TcpIoLoop.class.getName(),
//                        "Tcp loop closed already, input ip packet =" +
//                                inputIpPacket + ", tcp loop = " + this);
//                continue;
                    this.status = TcpIoLoopStatus.LISTEN;
                }
                if (this.status != TcpIoLoopStatus.LISTEN) {
                    Log.e(TcpIoLoop.class.getName(),
                            "RECEIVE SYN FAIL, because tcp loop not in LISTEN status, input ip packet = " +
                                    inputIpPacket +
                                    ", tcp loop = " + this);
                    continue;
                }
                Log.d(TcpIoLoop.class.getName(),
                        "RECEIVE SYN, initializing connection, input ip packet = " + inputIpPacket +
                                ", tcp loop = " + this);
                this.targetTcpBootstrap
                        .connect(this.destinationAddress, this.destinationPort).addListener(
                        (ChannelFutureListener) connectResultFuture -> {
                            if (!connectResultFuture.isSuccess()) {
                                Log.e(TcpIoLoop.class.getName(),
                                        "RECEIVE SYN FAIL, fail connect to target, input ip packet="
                                                + inputIpPacket + " tcp loop = " + TcpIoLoop.this);
                                synchronized (TcpIoLoop.this) {
                                    this.status = TcpIoLoopStatus.CLOSED;
                                }
                                return;
                            }
                            this.targetChannel = connectResultFuture.channel();
                            this.targetChannel.attr(TCP_LOOP).setIfAbsent(TcpIoLoop.this);
                            TcpHeaderOption mssOption = null;
                            for (TcpHeaderOption option : inputTcpHeader.getOptions()) {
                                if (option.getKind() == TcpHeaderOption.Kind.MSS) {
                                    mssOption = option;
                                    break;
                                }
                            }
                            if (mssOption != null) {
                                ByteBuf mssOptionBuf = Unpooled.wrappedBuffer(mssOption.getInfo());
                                this.mss = mssOptionBuf.readUnsignedShort();
                            }
                            this.window = inputTcpHeader.getWindow();
                            this.status = TcpIoLoopStatus.SYN_RECEIVED;
                            this.vpnToAppSequenceNumber = Math.abs(random.nextInt());
                            this.vpnToAppAcknowledgementNumber = this.appToVpnSequenceNumber + 1;
                            Log.d(TcpIoLoop.class.getName(),
                                    "RECEIVE SYN[DO ACK], connect to target success, switch tcp loop status to SYN_RECEIVED, input ip packet = " +
                                            inputIpPacket +
                                            ", tcp loop = " + this);
                            this.writeToApp(this.buildSynAck());
                        });
                continue;
            }
            if (!inputTcpHeader.isSyn() && inputTcpHeader.isAck()) {
                if (this.status == TcpIoLoopStatus.SYN_RECEIVED) {
//                    if (inputTcpHeader.getSequenceNumber() != this.vpnToAppAcknowledgementNumber) {
//                        Log.e(TcpIoLoop.class.getName(),
//                                "RECEIVE ACK, the seq number from app is not correct, input ip packet=" +
//                                        inputIpPacket + ", tcp loop = " + this);
//                        continue;
//                    }
//                    if (inputTcpHeader.getAcknowledgementNumber() != this.vpnToAppSequenceNumber + 1) {
//                        Log.e(TcpIoLoop.class.getName(),
//                                "RECEIVE ACK, the ack number from app is not correct, input ip packet=" +
//                                        inputIpPacket + ", tcp loop = " + this);
//                        continue;
//                    }
                    this.status = TcpIoLoopStatus.ESTABLISHED;
                    Log.d(TcpIoLoop.class.getName(),
                            "RECEIVE ACK, switch tcp loop to ESTABLISHED, input ip packet =" + inputIpPacket +
                                    ", tcp loop = " +
                                    this);
                    continue;
                }
                if (inputTcpHeader.isPsh()) {
                    //Psh ack
                    this.vpnToAppAcknowledgementNumber =
                            inputTcpHeader.getSequenceNumber() + inputTcpPacket.getData().length;
                    this.vpnToAppSequenceNumber = this.appToVpnAcknowledgementNumber;
                    Log.d(TcpIoLoop.class.getName(),
                            "RECEIVE PSH[DO ACK], write ACK to app side, input ip packet =" + inputIpPacket +
                                    ", tcp loop = " +
                                    this);
                    this.writeToApp(this.buildAck(null));
                    this.vpnToAppSequenceNumber++;
                    if (inputTcpPacket.getData().length > 0) {
                        Log.d(TcpIoLoop.class.getName(),
                                "RECEIVE PSH[DATA], send psh data to target, input ip packet =" + inputIpPacket +
                                        ", tcp loop = " +
                                        this);
                        ByteBuf pshData = Unpooled.wrappedBuffer(inputTcpPacket.getData());
                        Log.d(TcpIoLoop.class.getName(),
                                "RECEIVE PSH[DATA], PSH DATA:\n" + ByteBufUtil.prettyHexDump(pshData));
                        targetChannel.writeAndFlush(pshData);
                    }
                    continue;
                }
                if (this.status == TcpIoLoopStatus.ESTABLISHED) {
                    this.vpnToAppAcknowledgementNumber =
                            inputTcpHeader.getSequenceNumber() + 1;
                    this.vpnToAppSequenceNumber = this.appToVpnAcknowledgementNumber;
                    if (inputTcpHeader.isFin()) {
                        this.vpnToAppSequenceNumber = inputTcpPacket.getHeader().getAcknowledgementNumber();
                        this.vpnToAppAcknowledgementNumber = inputTcpPacket.getHeader().getSequenceNumber() + 1;
                        Log.d(TcpIoLoop.class.getName(),
                                "RECEIVE FIN ACK[DO ACK], input ip packet =" +
                                        inputIpPacket +
                                        ", tcp loop = " +
                                        this);
                        this.writeToApp(this.buildAck(null));
                        if (targetChannel == null) {
                            Log.d(TcpIoLoop.class.getName(),
                                    "RECEIVE FIN ACK[DO ACK], no target channel, return directly, input ip packet =" +
                                            inputIpPacket +
                                            ", tcp loop = " +
                                            this);
                            continue;
                        }
                        this.targetChannel.close().addListener(future -> {
                            this.destroy();
                            Log.d(TcpIoLoop.class.getName(),
                                    "RECEIVE FIN ACK[DO ACK], tcp loop closed, input ip packet =" +
                                            inputIpPacket +
                                            ", tcp loop = " +
                                            this);
                        });
                        return;
                    }
                    Log.d(TcpIoLoop.class.getName(),
                            "RECEIVE ACK[ESTABLISHED], input ip packet =" + inputIpPacket +
                                    ", tcp loop = " +
                                    this);
                    this.writeTargetDataSemaphore.release();
                    continue;
                }
                if (this.status == TcpIoLoopStatus.LAST_ACK) {
                    if (inputTcpHeader.getSequenceNumber() != this.vpnToAppSequenceNumber + 1) {
                        Log.e(TcpIoLoop.class.getName(),
                                "RECEIVE LAST_ACK FAIL, The ack number from app for last ack is not correct, input ip packet=" +
                                        inputIpPacket + ", tcp loop = " + this);
                        continue;
                    }
                    this.destroy();
                    Log.d(TcpIoLoop.class.getName(),
                            "RECEIVE LAST_ACK, close tcp loop, input ip packet =" + inputIpPacket + ", tcp loop = " +
                                    this);
                    return;
                }
                Log.e(TcpIoLoop.class.getName(),
                        "Tcp loop in illegal status, input ip packet = " + inputIpPacket + ", tcp loop = " + this);
                throw new IllegalStateException("Tcp loop[" + this.key + "] in illegal status");
            }
            if (inputTcpHeader.isFin()) {
                this.vpnToAppAcknowledgementNumber = inputTcpHeader.getSequenceNumber() + 1;
                this.status = TcpIoLoopStatus.CLOSE_WAIT;
                Log.d(TcpIoLoop.class.getName(),
                        "RECEIVE FIN, switch tcp loop status to CLOSE_WAIT, write ack to app side, input ip packet =" +
                                inputIpPacket + ", tcp loop = " +
                                this);
                this.writeToApp(this.buildAck(null));
                if (targetChannel == null) {
                    Log.d(TcpIoLoop.class.getName(),
                            "RECEIVE FIN, no target channel, return directly, input ip packet =" + inputIpPacket +
                                    ", tcp loop = " +
                                    this);
                    continue;
                }
                targetChannel.close().syncUninterruptibly().addListener((ChannelFutureListener) future -> {
                    if (!future.isSuccess()) {
                        Log.d(TcpIoLoop.class.getName(),
                                "RECEIVE FIN FAIL, fail to close target channel, input ip packet =" + inputIpPacket +
                                        ", tcp loop = " +
                                        this);
                        return;
                    }
                    this.status = TcpIoLoopStatus.LAST_ACK;
                    this.vpnToAppSequenceNumber++;
                    Log.d(TcpIoLoop.class.getName(),
                            "RECEIVE FIN, success to close target channel, write ack to app side, input ip packet =" +
                                    inputIpPacket + ", tcp loop = " +
                                    this);
                    this.writeToApp(this.buildFinAck(null));
                });
            }
        }
    }

    @Override
    public String toString() {
        return "TcpIoLoop{" +
                "key='" + key + '\'' +
                ", sourceAddress=" + sourceAddress +
                ", destinationAddress=" + destinationAddress +
                ", sourcePort=" + sourcePort +
                ", destinationPort=" + destinationPort +
                ", status=" + status +
                ", mss=" + mss +
                ", appToVpnSequenceNumber=" + appToVpnSequenceNumber +
                ", appToVpnAcknowledgementNumber=" + appToVpnAcknowledgementNumber +
                ", vpnToAppSequenceNumber=" + vpnToAppSequenceNumber +
                ", vpnToAppAcknowledgementNumber=" + vpnToAppAcknowledgementNumber +
                '}';
    }
}
