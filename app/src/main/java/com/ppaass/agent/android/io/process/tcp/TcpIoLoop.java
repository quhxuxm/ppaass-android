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
    private static final int BASE_SEQUENCE = (int) (Math.random() * 100000);
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
        writeTargetDataSemaphore = new Semaphore(1);
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

    public IpPacket buildFin(byte[] data) {
        TcpPacketBuilder ackTcpPacketBuilder = new TcpPacketBuilder();
        ackTcpPacketBuilder.fin(true);
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
        if (this.targetChannel != null) {
            try {
                if (this.targetChannel.isOpen()) {
                    this.targetChannel.close();
                }
            } catch (Exception e) {
                Log.e(TcpIoLoop.class.getName(), "Fail to close target channel on tcp loop destroy.", e);
            }
        }
        IoLoopHolder.INSTANCE.remove(this.getKey());
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
                inputIpPacket = this.ipPacketBlockingDeque.poll(20000L, TimeUnit.SECONDS);
                if (inputIpPacket == null) {
                    continue;
                }
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
            if (inputTcpHeader.isSyn()) {
                doSyn(inputIpPacket);
                continue;
            }
            if (inputTcpHeader.isAck()) {
                doAck(inputIpPacket);
                continue;
            }
            if (inputTcpHeader.isFin()) {
                doFin(inputIpPacket);
                continue;
            }
            if (inputTcpHeader.isRst()) {
                doRst(inputIpPacket);
                continue;
            }
        }
    }

    private void doSyn(IpPacket inputIpPacket) {
        TcpPacket inputTcpPacket = (TcpPacket) inputIpPacket.getData();
        TcpHeader inputTcpHeader = inputTcpPacket.getHeader();
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
                    this.vpnToAppSequenceNumber = BASE_SEQUENCE;
                    this.vpnToAppAcknowledgementNumber = inputTcpHeader.getSequenceNumber() + 1;
                    Log.d(TcpIoLoop.class.getName(),
                            "RECEIVE SYN[DO ACK], connect to target success, switch tcp loop status to SYN_RECEIVED, input ip packet = " +
                                    inputIpPacket +
                                    ", tcp loop = " + this);
                    this.writeToApp(this.buildSynAck());
                }).syncUninterruptibly();
    }

    private void doRst(IpPacket inputIpPacket) {
        TcpPacket inputTcpPacket = (TcpPacket) inputIpPacket.getData();
        TcpHeader inputTcpHeader = inputTcpPacket.getHeader();
        this.destroy();
    }

    private void doFin(IpPacket inputIpPacket) {
        TcpPacket inputTcpPacket = (TcpPacket) inputIpPacket.getData();
        TcpHeader inputTcpHeader = inputTcpPacket.getHeader();
        if (this.status == TcpIoLoopStatus.FIN_WAITE2) {
            this.status = TcpIoLoopStatus.TIME_WAITE;
            this.vpnToAppSequenceNumber++;
            this.vpnToAppAcknowledgementNumber = inputTcpHeader.getSequenceNumber() + 1;
            Log.d(TcpIoLoop.class.getName(),
                    "RECEIVE FIN on FIN_WAITE2[Change to TIME_WAIT],switch tcp loop status to TIME_WAITE, input ip packet=" +
                            inputIpPacket + ", tcp loop = " + this);
            this.writeToApp(this.buildAck(null));
            this.destroy();
            return;
        }
        this.vpnToAppAcknowledgementNumber = inputTcpHeader.getSequenceNumber() + 1;
        this.status = TcpIoLoopStatus.CLOSE_WAIT;
        Log.d(TcpIoLoop.class.getName(),
                "RECEIVE FIN[Change to CLOSE_WAIT], switch tcp loop status to CLOSE_WAIT, write ack to app side, input ip packet =" +
                        inputIpPacket + ", tcp loop = " +
                        this);
        this.writeToApp(this.buildAck(null));
        if (targetChannel == null) {
            Log.d(TcpIoLoop.class.getName(),
                    "RECEIVE FIN[Close directly], no target channel, return directly, input ip packet =" +
                            inputIpPacket +
                            ", tcp loop = " +
                            this);
            return;
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

    private void doAck(IpPacket inputIpPacket) {
        TcpPacket inputTcpPacket = (TcpPacket) inputIpPacket.getData();
        TcpHeader inputTcpHeader = inputTcpPacket.getHeader();
        if (this.status == TcpIoLoopStatus.FIN_WAITE1) {
            this.status = TcpIoLoopStatus.FIN_WAITE2;
            return;
        }
        if (this.status == TcpIoLoopStatus.SYN_RECEIVED) {
            if (inputTcpHeader.getSequenceNumber() != this.vpnToAppAcknowledgementNumber) {
                Log.e(TcpIoLoop.class.getName(),
                        "RECEIVE ACK, but the sequence number do not match the vpnToAppAcknowledgementNumber in tcp loop, input ip packet =" +
                                inputIpPacket +
                                ", tcp loop = " +
                                this);
                return;
            }
            this.vpnToAppSequenceNumber = inputTcpHeader.getAcknowledgementNumber();
            this.status = TcpIoLoopStatus.ESTABLISHED;
            Log.d(TcpIoLoop.class.getName(),
                    "RECEIVE ACK, switch tcp loop to ESTABLISHED, input ip packet =" + inputIpPacket +
                            ", tcp loop = " +
                            this);
            return;
        }
        if (inputTcpHeader.isPsh()) {
            //Psh ack
            this.vpnToAppAcknowledgementNumber++;
            this.vpnToAppSequenceNumber = inputTcpHeader.getAcknowledgementNumber();
            Log.d(TcpIoLoop.class.getName(),
                    "RECEIVE PSH[DO ACK], write ACK to app side, input ip packet =" + inputIpPacket +
                            ", tcp loop = " +
                            this);
            this.writeToApp(this.buildAck(null));
            if (inputTcpPacket.getData().length > 0) {
                Log.d(TcpIoLoop.class.getName(),
                        "RECEIVE PSH[DATA], send psh data to target, input ip packet =" + inputIpPacket +
                                ", tcp loop = " +
                                this);
                ByteBuf pshData = Unpooled.wrappedBuffer(inputTcpPacket.getData());
                Log.d(TcpIoLoop.class.getName(),
                        "RECEIVE PSH[DATA], PSH DATA:\n" + ByteBufUtil.prettyHexDump(pshData));
                targetChannel.writeAndFlush(pshData).syncUninterruptibly();
            }
            return;
        }
        if (this.status == TcpIoLoopStatus.ESTABLISHED) {
//            this.vpnToAppAcknowledgementNumber =
//                    inputTcpHeader.getSequenceNumber() + 1;
            this.vpnToAppSequenceNumber = inputTcpHeader.getAcknowledgementNumber();
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
                    return;
                }
                this.targetChannel.close().addListener(future -> {
                    this.destroy();
                    Log.d(TcpIoLoop.class.getName(),
                            "RECEIVE FIN ACK[DO ACK], tcp loop closed, input ip packet =" +
                                    inputIpPacket +
                                    ", tcp loop = " +
                                    this);
                }).syncUninterruptibly();
                return;
            }
            Log.d(TcpIoLoop.class.getName(),
                    "RECEIVE ACK[ESTABLISHED], input ip packet =" + inputIpPacket +
                            ", tcp loop = " +
                            this);
            this.writeTargetDataSemaphore.release();
            return;
        }
        if (this.status == TcpIoLoopStatus.LAST_ACK) {
            if (inputTcpHeader.getSequenceNumber() != this.vpnToAppSequenceNumber + 1) {
                Log.e(TcpIoLoop.class.getName(),
                        "RECEIVE LAST_ACK FAIL, The ack number from app for last ack is not correct, input ip packet=" +
                                inputIpPacket + ", tcp loop = " + this);
                return;
            }
            this.destroy();
            Log.d(TcpIoLoop.class.getName(),
                    "RECEIVE LAST_ACK, close tcp loop, input ip packet =" + inputIpPacket + ", tcp loop = " +
                            this);
            return;
        }
        if (this.status == TcpIoLoopStatus.FIN_WAITE2) {
            this.status = TcpIoLoopStatus.TIME_WAITE;
            this.vpnToAppSequenceNumber++;
            this.vpnToAppAcknowledgementNumber = inputTcpHeader.getSequenceNumber() + 1;
            Log.d(TcpIoLoop.class.getName(),
                    "RECEIVE ACK[FIN_WAITE2],switch tcp loop status to TIME_WAITE, input ip packet=" +
                            inputIpPacket + ", tcp loop = " + this);
            this.writeToApp(this.buildAck(null));
            this.destroy();
            return;
        }
        Log.e(TcpIoLoop.class.getName(),
                "Tcp loop in illegal status, input ip packet = " + inputIpPacket + ", tcp loop = " + this);
        throw new IllegalStateException("Tcp loop[" + this.key + "] in illegal status");
    }

    public synchronized void switchStatus(TcpIoLoopStatus status) {
        this.status = status;
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
                ", vpnToAppSequenceNumber=" + vpnToAppSequenceNumber +
                ", vpnToAppAcknowledgementNumber=" + vpnToAppAcknowledgementNumber +
                '}';
    }
}
