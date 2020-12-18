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

import static com.ppaass.agent.android.io.process.IIoConstant.TCP_LOOP;

public class TcpIoLoop implements IIoLoop {
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
    private long baseAppToVpnSequenceNumber;
    private long baseAppToVpnAcknowledgement;
    private long baseVpnToAppSequenceNumber;
    private long baseVpnToAppAcknowledgement;
    private long appToVpnSequenceNumber;
    private long appToVpnAcknowledgementNumber;
    private long vpnToAppSequenceNumber;
    private long vpnToAppAcknowledgementNumber;
    private int mss;

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
        this.baseAppToVpnSequenceNumber = 0;
        this.baseAppToVpnAcknowledgement = 0;
        this.baseVpnToAppSequenceNumber = 0;
        this.baseVpnToAppAcknowledgement = 0;
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
        IpV4Header ipV4Header =
                new IpV4HeaderBuilder()
                        .destinationAddress(this.sourceAddress.getAddress())
                        .sourceAddress(this.destinationAddress.getAddress())
                        .protocol(IpDataProtocol.TCP).build();
        tcpPacketBuilder
                .sequenceNumber(this.vpnToAppSequenceNumber)
                .acknowledgementNumber(this.vpnToAppAcknowledgementNumber)
                .destinationPort(this.sourcePort)
                .sourcePort(this.destinationPort).window(65535);
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

    @Override
    public final synchronized void execute(IpPacket inputIpPacket) {
        //Do some thing
        IIpHeader inputIpHeader = inputIpPacket.getHeader();
        if (inputIpHeader.getVersion() != IpHeaderVersion.V4) {
            Log.e(TcpIoLoop.class.getName(),
                    "Input ip package is not IPV4, ignore it, input ip packet = " + inputIpPacket + ", tcp loop = " +
                            this);
            return;
        }
        IpV4Header inputIpV4Header = (IpV4Header) inputIpHeader;
        if (inputIpV4Header.getProtocol() != IpDataProtocol.TCP) {
            Log.e(TcpIoLoop.class.getName(),
                    "Input ip package is not TCP, ignore it, input ip packet = " + inputIpPacket + ", tcp loop = " +
                            this);
            return;
        }
        TcpPacket inputTcpPacket = (TcpPacket) inputIpPacket.getData();
        TcpHeader inputTcpHeader = inputTcpPacket.getHeader();
        this.setAppToVpnSequenceNumber(inputTcpPacket.getHeader().getSequenceNumber());
        this.setAppToVpnAcknowledgementNumber(inputTcpPacket.getHeader().getAcknowledgementNumber());
        if (this.status == TcpIoLoopStatus.CLOSED) {
            Log.e(TcpIoLoop.class.getName(),
                    "Tcp loop closed already, input ip packet =" +
                            inputIpPacket + ", tcp loop = " + this);
            return;
        }
        if (inputTcpHeader.isSyn() && !inputTcpHeader.isAck()) {
            //Receive a syn.
            //First syn
            this.baseAppToVpnSequenceNumber = inputTcpPacket.getHeader().getSequenceNumber();
            this.baseVpnToAppSequenceNumber = Math.abs(random.nextInt());
            this.baseVpnToAppAcknowledgement = inputTcpPacket.getHeader().getSequenceNumber();
            if (this.status != TcpIoLoopStatus.LISTEN) {
                Log.e(TcpIoLoop.class.getName(),
                        "DO SYN FAIL, because tcp loop not in LISTEN status, input ip packet = " + inputIpPacket +
                                ", tcp loop = " + this);
                return;
            }
            Log.d(TcpIoLoop.class.getName(),
                    "DO SYN, initializing connection, input ip packet = " + inputIpPacket +
                            ", tcp loop = " + this);
            this.targetTcpBootstrap
                    .connect(this.destinationAddress, this.destinationPort).addListener(
                    (ChannelFutureListener) connectResultFuture -> {
                        if (!connectResultFuture.isSuccess()) {
                            Log.e(TcpIoLoop.class.getName(),
                                    "DO SYN FAIL, fail connect to target, input ip packet="
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
                        this.status = TcpIoLoopStatus.SYN_RECEIVED;
                        this.vpnToAppAcknowledgementNumber = this.baseAppToVpnSequenceNumber + 1;
                        this.vpnToAppSequenceNumber = this.baseVpnToAppSequenceNumber;
                        Log.d(TcpIoLoop.class.getName(),
                                "DO SYN ACK, connect to target success, switch tcp loop status to SYN_RECEIVED, input ip packet = " +
                                        inputIpPacket +
                                        ", tcp loop = " + this);
                        this.writeToApp(this.buildSynAck());
                    });
            return;
        }
        if (!inputTcpHeader.isSyn() && inputTcpHeader.isAck()) {
            if (this.status == TcpIoLoopStatus.LISTEN) {
                Log.e(TcpIoLoop.class.getName(),
                        "DO ACK, a duplicate ack coming destroy the loop instance, ignore the ip packet, input ip packet = " +
                                inputIpPacket + ", tcp loop = " + this);
                this.destroy();
                return;
            }
            if (this.status == TcpIoLoopStatus.SYN_RECEIVED) {
                if (inputTcpHeader.getSequenceNumber() != this.vpnToAppAcknowledgementNumber) {
                    Log.e(TcpIoLoop.class.getName(),
                            "DO ACK, the ack number from app is not correct, input ip packet=" +
                                    inputIpPacket + ", tcp loop = " + this);
                    return;
                }
                this.baseAppToVpnAcknowledgement = inputTcpHeader.getAcknowledgementNumber();
                this.appToVpnAcknowledgementNumber++;
                this.status = TcpIoLoopStatus.ESTABLISHED;
                Log.d(TcpIoLoop.class.getName(),
                        "DO ACK, switch tcp loop to ESTABLISHED, input ip packet =" + inputIpPacket + ", tcp loop = " +
                                this);
                return;
            }
            if (inputTcpHeader.isPsh()) {
                //Psh ack
                this.vpnToAppAcknowledgementNumber =
                        inputTcpHeader.getSequenceNumber() + inputTcpPacket.getData().length;
                this.vpnToAppSequenceNumber++;
                Log.d(TcpIoLoop.class.getName(),
                        "DO PSH[ACK], write ACK to app side, input ip packet =" + inputIpPacket + ", tcp loop = " +
                                this);
                this.writeToApp(this.buildAck(null));
                if (inputTcpPacket.getData().length > 0) {
                    Log.d(TcpIoLoop.class.getName(),
                            "DO PSH[DATA], send psh data to target, input ip packet =" + inputIpPacket +
                                    ", tcp loop = " +
                                    this);
                    ByteBuf pshData = Unpooled.wrappedBuffer(inputTcpPacket.getData());
                    Log.d(TcpIoLoop.class.getName(),
                            "DO PSH[DATA], PSH DATA:\n" + ByteBufUtil.prettyHexDump(pshData));
                    targetChannel.writeAndFlush(pshData);
                }
                return;
            }
            if (this.status == TcpIoLoopStatus.ESTABLISHED) {
                this.vpnToAppAcknowledgementNumber =
                        inputTcpHeader.getSequenceNumber() + inputTcpPacket.getData().length;
                this.vpnToAppSequenceNumber++;
                Log.d(TcpIoLoop.class.getName(),
                        "DO ACK[ESTABLISHED ACK], write ack to app side, input ip packet =" + inputIpPacket +
                                ", tcp loop = " +
                                this);
                this.writeToApp(this.buildAck(null));
                if (inputTcpPacket.getData().length > 0) {
                    Log.d(TcpIoLoop.class.getName(),
                            "DO ACK[ESTABLISHED DATA], send ack data to target, input ip packet =" + inputIpPacket +
                                    ", tcp loop = " +
                                    this);
                    ByteBuf ackData = Unpooled.wrappedBuffer(inputTcpPacket.getData());
                    Log.d(TcpIoLoop.class.getName(),
                            "DO ACK[ESTABLISHED DATA], ACK DATA:\n" + ByteBufUtil.prettyHexDump(ackData));
                    targetChannel.writeAndFlush(ackData);
                }
                return;
            }
            if (this.status == TcpIoLoopStatus.LAST_ACK) {
                if (inputTcpHeader.getSequenceNumber() != this.vpnToAppSequenceNumber + 1) {
                    Log.e(TcpIoLoop.class.getName(),
                            "DO LAST_ACK FAIL, The ack number from app for last ack is not correct, input ip packet=" +
                                    inputIpPacket + ", tcp loop = " + this);
                    return;
                }
                this.status = TcpIoLoopStatus.CLOSED;
                this.destroy();
                Log.d(TcpIoLoop.class.getName(),
                        "DO LAST_ACK, close tcp loop, input ip packet =" + inputIpPacket + ", tcp loop = " +
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
                    "DO FIN, switch tcp loop status to CLOSE_WAIT, write ack to app side, input ip packet =" +
                            inputIpPacket + ", tcp loop = " +
                            this);
            this.writeToApp(this.buildAck(null));
            if (targetChannel == null) {
                Log.d(TcpIoLoop.class.getName(),
                        "DO FIN, no target channel, return directly, input ip packet =" + inputIpPacket +
                                ", tcp loop = " +
                                this);
                return;
            }
            targetChannel.close().syncUninterruptibly().addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    Log.d(TcpIoLoop.class.getName(),
                            "DO FIN FAIL, fail to close target channel, input ip packet =" + inputIpPacket +
                                    ", tcp loop = " +
                                    this);
                    return;
                }
                this.status = TcpIoLoopStatus.LAST_ACK;
                Log.d(TcpIoLoop.class.getName(),
                        "DO FIN, success to close target channel, write ack to app side, input ip packet =" +
                                inputIpPacket + ", tcp loop = " +
                                this);
                this.writeToApp(this.buildAck(null));
            });
        }
    }

    public int getMss() {
        return mss;
    }

    @Override
    public void destroy() {
        this.status = TcpIoLoopStatus.CLOSED;
        IoLoopHolder.INSTANCE.remove(this.getKey());
    }

    public long getBaseAppToVpnSequenceNumber() {
        return baseAppToVpnSequenceNumber;
    }

    public void setBaseAppToVpnSequenceNumber(long baseAppToVpnSequenceNumber) {
        if (this.baseAppToVpnSequenceNumber > 0) {
            return;
        }
        this.baseAppToVpnSequenceNumber = baseAppToVpnSequenceNumber;
    }

    public long getBaseAppToVpnAcknowledgement() {
        return baseAppToVpnAcknowledgement;
    }

    public void setBaseAppToVpnAcknowledgement(long baseAppToVpnAcknowledgement) {
        if (this.baseAppToVpnAcknowledgement > 0) {
            return;
        }
        this.baseAppToVpnAcknowledgement = baseAppToVpnAcknowledgement;
    }

    public long getBaseVpnToAppSequenceNumber() {
        return baseVpnToAppSequenceNumber;
    }

    public void setBaseVpnToAppSequenceNumber(long baseVpnToAppSequenceNumber) {
        if (this.baseVpnToAppSequenceNumber > 0) {
            return;
        }
        this.baseVpnToAppSequenceNumber = baseVpnToAppSequenceNumber;
    }

    public long getBaseVpnToAppAcknowledgement() {
        return baseVpnToAppAcknowledgement;
    }

    public void setBaseVpnToAppAcknowledgement(long baseVpnToAppAcknowledgement) {
        if (this.baseVpnToAppAcknowledgement > 0) {
            return;
        }
        this.baseVpnToAppAcknowledgement = baseVpnToAppAcknowledgement;
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



    @Override
    public String toString() {
        return "TcpIoLoop{" +
                "key='" + key + '\'' +
                ", sourceAddress=" + sourceAddress +
                ", destinationAddress=" + destinationAddress +
                ", sourcePort=" + sourcePort +
                ", destinationPort=" + destinationPort +
                ", status=" + status +
                ", baseAppToVpnSequenceNumber=" + baseAppToVpnSequenceNumber +
                ", baseAppToVpnAcknowledgement=" + baseAppToVpnAcknowledgement +
                ", baseVpnToAppSequenceNumber=" + baseVpnToAppSequenceNumber +
                ", baseVpnToAppAcknowledgement=" + baseVpnToAppAcknowledgement +
                ", appToVpnSequenceNumber=" + appToVpnSequenceNumber +
                ", appToVpnAcknowledgementNumber=" + appToVpnAcknowledgementNumber +
                ", vpnToAppSequenceNumber=" + vpnToAppSequenceNumber +
                ", vpnToAppAcknowledgementNumber=" + vpnToAppAcknowledgementNumber +
                ", appToVpnSequenceNumber[RELATIVE]=" + (appToVpnSequenceNumber - this.baseAppToVpnSequenceNumber) +
                ", appToVpnAcknowledgementNumber[RELATIVE]=" +
                (appToVpnAcknowledgementNumber - this.baseAppToVpnAcknowledgement) +
                ", vpnToAppSequenceNumber[RELATIVE]=" + (vpnToAppSequenceNumber - this.baseVpnToAppSequenceNumber) +
                ", vpnToAppAcknowledgementNumber[RELATIVE]=" +
                (vpnToAppAcknowledgementNumber - this.baseVpnToAppAcknowledgement) +
                ", mss=" + mss +
                '}';
    }
}
