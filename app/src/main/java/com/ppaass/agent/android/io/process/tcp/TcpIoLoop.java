package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import com.ppaass.agent.android.io.process.IIoLoop;
import com.ppaass.agent.android.io.process.IoLoopHolder;
import com.ppaass.agent.android.io.protocol.ip.*;
import com.ppaass.agent.android.io.protocol.tcp.TcpHeader;
import com.ppaass.agent.android.io.protocol.tcp.TcpHeaderOption;
import com.ppaass.agent.android.io.protocol.tcp.TcpPacket;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.InetAddress;

import static com.ppaass.agent.android.io.process.IIoConstant.TCP_LOOP;

public class TcpIoLoop implements IIoLoop {
    private static final int DEFAULT_WINDOW_SIZE_IN_BYTE = 65535;
    private static final int BASE_SEQUENCE = (int) (Math.random() * 100000);
    private final InetAddress sourceAddress;
    private final InetAddress destinationAddress;
    private final int sourcePort;
    private final int destinationPort;
    private final String key;
    private TcpIoLoopStatus status;
    private final Bootstrap targetTcpBootstrap;
    private Channel targetChannel;
    private final FileOutputStream vpnOutputStream;
    private long vpnToAppSequenceNumber;
    private long vpnToAppAcknowledgementNumber;
    private int mss;
    //    private final Semaphore writeTargetDataSemaphore;
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
    }

    @Override
    public String getKey() {
        return this.key;
    }

    @Override
    public OutputStream getVpnOutput() {
        return vpnOutputStream;
    }

    public int getMss() {
        return mss;
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

    public int getWindow() {
        return window;
    }

    @Override
    public final synchronized void execute(IpPacket inputIpPacket) {
        //Do some thing
        IIpHeader inputIpHeader = inputIpPacket.getHeader();
        if (inputIpHeader.getVersion() != IpHeaderVersion.V4) {
            Log.e(TcpIoLoop.class.getName(),
                    "Input ip package is not IPV4, ignore it, input ip packet = " + inputIpPacket +
                            ", tcp loop = " +
                            this);
            this.destroy();
            return;
        }
        IpV4Header inputIpV4Header = (IpV4Header) inputIpHeader;
        if (inputIpV4Header.getProtocol() != IpDataProtocol.TCP) {
            Log.e(TcpIoLoop.class.getName(),
                    "Input ip package is not TCP, ignore it, input ip packet = " + inputIpPacket + ", tcp loop = " +
                            this);
            this.destroy();
            return;
        }
        TcpPacket inputTcpPacket = (TcpPacket) inputIpPacket.getData();
        TcpHeader inputTcpHeader = inputTcpPacket.getHeader();
        if (inputTcpHeader.isSyn()) {
            doSyn(inputIpPacket);
            return;
        }
        if (inputTcpHeader.isAck()) {
            doAck(inputIpPacket);
            return;
        }
        if (inputTcpHeader.isFin()) {
            doFin(inputIpPacket);
            return;
        }
        if (inputTcpHeader.isRst()) {
            doRst(inputIpPacket);
            return;
        }
    }

    @Override
    public synchronized void destroy() {
        Log.d(TcpIoLoop.class.getName(), "Destroy target channel of tcp loop, tcp loop = " + this);
        this.status = TcpIoLoopStatus.CLOSED;
        this.vpnToAppAcknowledgementNumber = 0;
        this.vpnToAppSequenceNumber = 0;
        if (this.targetChannel != null) {
            try {
                if (this.targetChannel.isOpen()) {
                    this.targetChannel.close();
                }
                this.targetChannel = null;
            } catch (Exception e) {
                Log.e(TcpIoLoop.class.getName(), "Fail to close target channel on tcp loop destroy.", e);
            }
        }
        IoLoopHolder.INSTANCE.remove(this.getKey());
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
                            this.destroy();
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
                    TcpIoLoopOutputWriter.INSTANCE.writeSynAckForTcpIoLoop(this);
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
//        if (this.status == TcpIoLoopStatus.FIN_WAITE2) {
//            this.status = TcpIoLoopStatus.TIME_WAITE;
//            this.vpnToAppSequenceNumber++;
//            this.vpnToAppAcknowledgementNumber = inputTcpHeader.getSequenceNumber() + 1;
//            Log.d(TcpIoLoop.class.getName(),
//                    "RECEIVE FIN on FIN_WAITE2[Change to TIME_WAIT],switch tcp loop status to TIME_WAITE, input ip packet=" +
//                            inputIpPacket + ", tcp loop = " + this);
//            TcpIoLoopOutputWriter.INSTANCE.writeAckForTcpIoLoop(this, null);
//            this.destroy();
//            return;
//        }
        this.vpnToAppAcknowledgementNumber = inputTcpHeader.getSequenceNumber() + 1;
        this.status = TcpIoLoopStatus.CLOSE_WAIT;
        Log.d(TcpIoLoop.class.getName(),
                "RECEIVE FIN[Change to CLOSE_WAIT], switch tcp loop status to CLOSE_WAIT, write ack to app side, input ip packet =" +
                        inputIpPacket + ", tcp loop = " +
                        this);
        TcpIoLoopOutputWriter.INSTANCE.writeAckForTcpIoLoop(this, null);
        if (targetChannel == null) {
            Log.d(TcpIoLoop.class.getName(),
                    "RECEIVE FIN[Close directly], no target channel, return directly, input ip packet =" +
                            inputIpPacket +
                            ", tcp loop = " +
                            this);
            TcpIoLoopOutputWriter.INSTANCE.writeAckForTcpIoLoop(this, null);
            this.status = TcpIoLoopStatus.LAST_ACK;
            return;
        }
        targetChannel.close().syncUninterruptibly();
        TcpIoLoopOutputWriter.INSTANCE.writeAckForTcpIoLoop(this, null);
        this.status = TcpIoLoopStatus.LAST_ACK;
        Log.d(TcpIoLoop.class.getName(),
                "RECEIVE FIN, success to close target channel, write ack to app side, input ip packet =" +
                        inputIpPacket + ", tcp loop = " +
                        this);
    }

    private void doAck(IpPacket inputIpPacket) {
        TcpPacket inputTcpPacket = (TcpPacket) inputIpPacket.getData();
        TcpHeader inputTcpHeader = inputTcpPacket.getHeader();
        if (this.status == TcpIoLoopStatus.FIN_WAITE1) {
            Log.d(TcpIoLoop.class.getName(),
                    "RECEIVE ACK[For FIN_WAITE1], switch tcp loop status to FIN_WAITE2, input ip packet =" +
                            inputIpPacket +
                            ", tcp loop = " +
                            this);
            this.status = TcpIoLoopStatus.FIN_WAITE2;
            return;
        }
        if (this.status == TcpIoLoopStatus.SYN_RECEIVED) {
            if (inputTcpHeader.getSequenceNumber() != this.vpnToAppAcknowledgementNumber) {
                Log.e(TcpIoLoop.class.getName(),
                        "RECEIVE ACK[For SYN_RECEIVED], but the sequence number do not match the vpnToAppAcknowledgementNumber in tcp loop, input ip packet =" +
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
        if (this.status == TcpIoLoopStatus.ESTABLISHED) {
            if (inputTcpHeader.isPsh()) {
                //Psh ack
                this.vpnToAppSequenceNumber = inputTcpHeader.getAcknowledgementNumber();
                Log.d(TcpIoLoop.class.getName(),
                        "RECEIVE PSH[DO ACK], write ACK to app side, input ip packet =" + inputIpPacket +
                                ", tcp loop = " +
                                this);
                TcpIoLoopOutputWriter.INSTANCE.writeAckForTcpIoLoop(this, null);
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
                this.vpnToAppAcknowledgementNumber = this.vpnToAppAcknowledgementNumber + inputTcpPacket.getData().length;
                Log.d(TcpIoLoop.class.getName(),
                        "RECEIVE PSH[DO ACK - Finish Write], write ACK to app side, input ip packet =" + inputIpPacket +
                                ", tcp loop = " +
                                this);
                return;
            }
//            this.vpnToAppAcknowledgementNumber =
//                    inputTcpHeader.getSequenceNumber() + 1;
            if (inputTcpHeader.isFin()) {
                this.vpnToAppSequenceNumber = inputTcpPacket.getHeader().getAcknowledgementNumber();
                this.vpnToAppAcknowledgementNumber = inputTcpPacket.getHeader().getSequenceNumber() + 1;
                Log.d(TcpIoLoop.class.getName(),
                        "RECEIVE FIN ACK[DO ACK], input ip packet =" +
                                inputIpPacket +
                                ", tcp loop = " +
                                this);
                TcpIoLoopOutputWriter.INSTANCE.writeAckForTcpIoLoop(this, null);
                if (targetChannel == null) {
                    Log.d(TcpIoLoop.class.getName(),
                            "RECEIVE FIN ACK[DO ACK], no target channel, return directly, input ip packet =" +
                                    inputIpPacket +
                                    ", tcp loop = " +
                                    this);
                    TcpIoLoopOutputWriter.INSTANCE.writeAckForTcpIoLoop(this, null);
                    this.status=TcpIoLoopStatus.LAST_ACK;
                    return;
                }
                this.targetChannel.close().syncUninterruptibly();
                TcpIoLoopOutputWriter.INSTANCE.writeAckForTcpIoLoop(this, null);
                Log.d(TcpIoLoop.class.getName(),
                        "RECEIVE FIN ACK[DO ACK], tcp loop closed, input ip packet =" +
                                inputIpPacket +
                                ", tcp loop = " +
                                this);
                this.status=TcpIoLoopStatus.LAST_ACK;
                return;
            }
            this.vpnToAppSequenceNumber = inputTcpHeader.getAcknowledgementNumber();
            Log.d(TcpIoLoop.class.getName(),
                    "RECEIVE ACK[ESTABLISHED], input ip packet =" + inputIpPacket +
                            ", tcp loop = " +
                            this);
            if (inputTcpPacket.getData().length > 0) {
                Log.d(TcpIoLoop.class.getName(),
                        "RECEIVE ACK[ESTABLISHED with DATA], send data to target, input ip packet =" + inputIpPacket +
                                ", tcp loop = " +
                                this);
                ByteBuf ackData = Unpooled.wrappedBuffer(inputTcpPacket.getData());
                Log.d(TcpIoLoop.class.getName(),
                        "RECEIVE ACK[ESTABLISHED with DATA], ACK DATA:\n" + ByteBufUtil.prettyHexDump(ackData));
                targetChannel.writeAndFlush(ackData).syncUninterruptibly();
                return;
            }
            return;
        }
        if (this.status == TcpIoLoopStatus.LAST_ACK) {
            Log.d(TcpIoLoop.class.getName(),
                    "RECEIVE LAST_ACK, close tcp loop, input ip packet =" + inputIpPacket + ", tcp loop = " +
                            this);
            this.destroy();
            return;
        }
        if (this.status == TcpIoLoopStatus.FIN_WAITE2) {
            this.status = TcpIoLoopStatus.TIME_WAITE;
            this.vpnToAppSequenceNumber++;
            this.vpnToAppAcknowledgementNumber = inputTcpHeader.getSequenceNumber() + 1;
            Log.d(TcpIoLoop.class.getName(),
                    "RECEIVE ACK[FIN_WAITE2],switch tcp loop status to TIME_WAITE, input ip packet=" +
                            inputIpPacket + ", tcp loop = " + this);
            TcpIoLoopOutputWriter.INSTANCE.writeAckForTcpIoLoop(this, null);
            this.destroy();
            return;
        }
        Log.e(TcpIoLoop.class.getName(),
                "Tcp loop in illegal status, input ip packet = " + inputIpPacket + ", tcp loop = " + this);
        TcpIoLoopOutputWriter.INSTANCE.writeRstForTcpIoLoop(this);
        this.destroy();
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
