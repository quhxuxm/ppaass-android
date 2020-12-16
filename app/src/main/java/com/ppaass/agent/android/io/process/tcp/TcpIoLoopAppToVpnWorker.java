package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
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
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;

import java.util.concurrent.BlockingDeque;

import static com.ppaass.agent.android.io.process.IIoConstant.TCP_LOOP;

class TcpIoLoopAppToVpnWorker implements Runnable {
    private final TcpIoLoop tcpIoLoop;
    private final Bootstrap proxyTcpBootstrap;
    private boolean alive;
    private Channel targetChannel = null;
    private final BlockingDeque<IpPacket> appToVpnIpPacketQueue;
    private final BlockingDeque<TcpIoLoopVpntoAppData> outputDataQueue;

    TcpIoLoopAppToVpnWorker(TcpIoLoop tcpIoLoop,
                            Bootstrap proxyTcpBootstrap,
                            BlockingDeque<IpPacket> appToVpnIpPacketQueue,
                            BlockingDeque<TcpIoLoopVpntoAppData> outputDataQueue) {
        this.tcpIoLoop = tcpIoLoop;
        this.proxyTcpBootstrap = proxyTcpBootstrap;
        this.appToVpnIpPacketQueue = appToVpnIpPacketQueue;
        this.outputDataQueue = outputDataQueue;
        this.alive = false;
    }

    public void start() {
        this.alive = true;
        this.tcpIoLoop.switchStatus(TcpIoLoopStatus.LISTEN);
    }

    public void stop() {
        Log.d(TcpIoLoopAppToVpnWorker.class.getName(), "Stop app to vpn worker, tcp loop = " + this.tcpIoLoop);
        this.alive = false;
        this.appToVpnIpPacketQueue.clear();
        IoLoopHolder.INSTANCE.getIoLoops().remove(this.tcpIoLoop.getKey());
    }

    public void offerIpPacket(IpPacket ipPacket) {
        try {
            this.appToVpnIpPacketQueue.put(ipPacket);
        } catch (InterruptedException e) {
            Log.e(TcpIoLoopAppToVpnWorker.class.getName(),
                    "Fail to put ip packet to the queue, tcp loop = " + this.tcpIoLoop);
        }
    }

    @Override
    public void run() {
        while (alive) {
            final IpPacket inputIpPacket;
            try {
                inputIpPacket = this.appToVpnIpPacketQueue.take();
            } catch (InterruptedException e) {
                this.stop();
                return;
            }
            //Do some thing
            IIpHeader inputIpHeader = inputIpPacket.getHeader();
            if (inputIpHeader.getVersion() != IpHeaderVersion.V4) {
                this.stop();
                return;
            }
            IpV4Header inputIpV4Header = (IpV4Header) inputIpHeader;
            if (inputIpV4Header.getProtocol() != IpDataProtocol.TCP) {
                this.stop();
                return;
            }
            TcpPacket inputTcpPacket = (TcpPacket) inputIpPacket.getData();
            TcpHeader inputTcpHeader = inputTcpPacket.getHeader();
//            if ((inputTcpHeader.getSequenceNumber() == this.tcpIoLoop.getAppToVpnSequenceNumber() &&
//                    inputTcpHeader.getAcknowledgementNumber() == this.tcpIoLoop.getAppToVpnAcknowledgementNumber()) &&
//                    this.tcpIoLoop.getStatus() == TcpIoLoopStatus.SYN_RECEIVED) {
//                Log.d(TcpIoLoopAppToVpnWorker.class.getName(),
//                        "Ignore duplicate tcp packet, input ip packet = " + inputIpPacket + ", tcp loop = " +
//                                this.tcpIoLoop);
//                continue;
//            }
            long sequenceNumberInTcpHeader = inputTcpHeader.getSequenceNumber();
            long acknowledgementNumberInTcpHeader = inputTcpHeader.getAcknowledgementNumber();
            this.tcpIoLoop.setAppToVpnSequenceNumber(sequenceNumberInTcpHeader);
            this.tcpIoLoop.setAppToVpnAcknowledgementNumber(acknowledgementNumberInTcpHeader);
            if (this.tcpIoLoop.getStatus() == TcpIoLoopStatus.CLOSED) {
                Log.e(TcpIoLoopAppToVpnWorker.class.getName(),
                        "Tcp loop closed already, input ip packet =" +
                                inputIpPacket + ", tcp loop = " + this.tcpIoLoop);
                this.stop();
                return;
            }
            if (inputTcpHeader.isSyn() && !inputTcpHeader.isAck()) {
                //Receive a syn.
                if (this.tcpIoLoop.getStatus() == TcpIoLoopStatus.LISTEN) {
                    //First syn
                    ChannelFuture connectResult = this.proxyTcpBootstrap
                            .connect(tcpIoLoop.getDestinationAddress(), tcpIoLoop.getDestinationPort())
                            .syncUninterruptibly();
                    if (!connectResult.isSuccess()) {
                        Log.e(TcpIoLoopProxyToVpnHandler.class.getName(),
                                "Fail connect to target, input ip packet="
                                        + inputIpPacket + " tcp loop = " + tcpIoLoop);
                        continue;
                    }
                    this.targetChannel = connectResult.channel();
                    this.targetChannel.attr(TCP_LOOP).setIfAbsent(this.tcpIoLoop);
                    this.tcpIoLoop.setVpnToAppAcknowledgementNumber(sequenceNumberInTcpHeader + 1);
                    this.tcpIoLoop.setVpnToAppSequenceNumber(0);
                    TcpHeaderOption mssOption = null;
                    for (TcpHeaderOption option : inputTcpHeader.getOptions()) {
                        if (option.getKind() == TcpHeaderOption.Kind.MSS) {
                            mssOption = option;
                            break;
                        }
                    }
                    if (mssOption != null) {
                        ByteBuf mssOptionBuf = Unpooled.wrappedBuffer(mssOption.getInfo());
                        this.tcpIoLoop.setMss(mssOptionBuf.readUnsignedShort());
                    }
                    this.tcpIoLoop.switchStatus(TcpIoLoopStatus.SYN_RECEIVED);
                    TcpIoLoopVpntoAppData outputData = new TcpIoLoopVpntoAppData();
                    outputData.setCommand(TcpIoLoopVpnToAppCommand.DO_SYN_ACK);
                    Log.d(TcpIoLoopProxyToVpnHandler.class.getName(),
                            "Receive proxy connect success response, tcp loop = " + tcpIoLoop +
                                    ", tcp output data = " +
                                    outputData);
                    this.tcpIoLoop.offerOutputData(outputData);
                    continue;
                }
                throw new IllegalStateException();
            }
            if (!inputTcpHeader.isSyn() && inputTcpHeader.isAck()) {
                if (this.tcpIoLoop.getStatus() == TcpIoLoopStatus.SYN_RECEIVED) {
                    if (inputTcpHeader.getSequenceNumber() != tcpIoLoop.getVpnToAppAcknowledgementNumber()) {
                        Log.e(TcpIoLoopAppToVpnWorker.class.getName(),
                                "The ack number from app is not correct, input ip packet=" +
                                        inputIpPacket + ", tcp loop = " + this.tcpIoLoop);
                        continue;
                    }
                    this.tcpIoLoop.switchStatus(TcpIoLoopStatus.ESTABLISHED);
                    Log.d(TcpIoLoopAppToVpnWorker.class.getName(),
                            "Switch tcp loop to ESTABLISHED, input ip packet =" + inputIpPacket + ", tcp loop = " +
                                    this.tcpIoLoop);
                    continue;
                }
                if (inputTcpHeader.isPsh()) {
                    this.tcpIoLoop.setVpnToAppSequenceNumber(acknowledgementNumberInTcpHeader);
                    this.tcpIoLoop.setVpnToAppAcknowledgementNumber(
                       sequenceNumberInTcpHeader+ inputTcpPacket.getData().length);
                    if (inputTcpPacket.getData().length > 0) {
                        if (this.targetChannel == null) {
                            Log.e(TcpIoLoopAppToVpnWorker.class.getName(),
                                    "The proxy channel is null when psh, input ip packet =" + inputIpPacket +
                                            ", tcp loop = " +
                                            this.tcpIoLoop);
                            this.tcpIoLoop.stop();
                            return;
                        }
                        ByteBuf dataSendToTarget = Unpooled.wrappedBuffer(inputTcpPacket.getData());
                        Log.d(TcpIoLoopAppToVpnWorker.class.getName(),
                                "PSH DATA:\n" + ByteBufUtil.prettyHexDump(dataSendToTarget) + "\n");
                        TcpIoLoopVpntoAppData ackData = new TcpIoLoopVpntoAppData();
                        ackData.setCommand(TcpIoLoopVpnToAppCommand.DO_ACK);
                        Log.d(TcpIoLoopAppToVpnWorker.class.getName(),
                                "There is data psh ack packet, write it to proxy, input ip packet = " +
                                        inputIpPacket + ", tcp loop = " + this.tcpIoLoop);
                        try {
                            this.outputDataQueue.put(ackData);
                        } catch (InterruptedException e) {
                            Log.e(TcpIoLoopAppToVpnWorker.class.getName(),
                                    "Fail to do push ack because of exception, input ip packet =" + inputIpPacket +
                                            ",tcp loop = " + this.tcpIoLoop, e);
                        }
                        targetChannel.writeAndFlush(dataSendToTarget).syncUninterruptibly();
                    }
                    continue;
                }
                if (this.tcpIoLoop.getStatus() == TcpIoLoopStatus.ESTABLISHED) {
                    if (this.targetChannel == null) {
                        Log.e(TcpIoLoopAppToVpnWorker.class.getName(),
                                "The proxy channel is null when ack[ESTABLISHED], input ip packet =" +
                                        inputIpPacket +
                                        ", tcp loop = " +
                                        this.tcpIoLoop);
                        this.tcpIoLoop.stop();
                        return;
                    }
                    this.tcpIoLoop.setVpnToAppSequenceNumber(sequenceNumberInTcpHeader);
                    this.tcpIoLoop.setVpnToAppAcknowledgementNumber(
                           sequenceNumberInTcpHeader+ inputTcpPacket.getData().length);
                    if (inputTcpPacket.getData().length > 0) {
                        TcpIoLoopVpntoAppData ackData = new TcpIoLoopVpntoAppData();
                        ackData.setCommand(TcpIoLoopVpnToAppCommand.DO_ACK);
                        Log.d(TcpIoLoopAppToVpnWorker.class.getName(),
                                "There is data ack[ESTABLISHED] packet, write it to proxy, input ip packet = " +
                                        inputIpPacket + ", tcp loop = " + this.tcpIoLoop);
                        try {
                            this.outputDataQueue.put(ackData);
                        } catch (InterruptedException e) {
                            Log.e(TcpIoLoopAppToVpnWorker.class.getName(),
                                    "Fail to do ack[ESTABLISHED] because of exception, input ip packet =" +
                                            inputIpPacket +
                                            ",tcp loop = " + this.tcpIoLoop, e);
                        }
                        Log.d(TcpIoLoopAppToVpnWorker.class.getName(),
                                "Receive ack packet along with data, write it to proxy, input ip packet = " +
                                        inputIpPacket + ", tcp loop = " + this.tcpIoLoop);
                        ByteBuf dataSendToTarget = Unpooled.wrappedBuffer(inputTcpPacket.getData());
                        Log.d(TcpIoLoopAppToVpnWorker.class.getName(),
                                "ACK(ESTABLISHED) DATA:\n" + ByteBufUtil.prettyHexDump(
                                        dataSendToTarget) + "\n");
                        targetChannel.writeAndFlush(dataSendToTarget).syncUninterruptibly();
                    }
                    continue;
                }
                if (this.tcpIoLoop.getStatus() == TcpIoLoopStatus.LAST_ACK) {
                    if (inputTcpHeader.getSequenceNumber() != this.tcpIoLoop.getVpnToAppSequenceNumber() + 1) {
                        Log.e(TcpIoLoopAppToVpnWorker.class.getName(),
                                "The ack number from app for last ack is not correct, input ip packet=" +
                                        inputIpPacket + ", tcp loop = " + this.tcpIoLoop);
                        continue;
                    }
                    this.tcpIoLoop.switchStatus(TcpIoLoopStatus.CLOSED);
                    continue;
                }
                throw new IllegalStateException();
            }
            if (inputTcpHeader.isFin()) {
                TcpIoLoopVpntoAppData finAckOutputData = new TcpIoLoopVpntoAppData();
                finAckOutputData.setCommand(TcpIoLoopVpnToAppCommand.DO_FIN_ACK);
                this.tcpIoLoop.setVpnToAppAcknowledgementNumber(sequenceNumberInTcpHeader+ 1);
                this.tcpIoLoop.setVpnToAppSequenceNumber(sequenceNumberInTcpHeader);
                this.tcpIoLoop.switchStatus(TcpIoLoopStatus.CLOSE_WAIT);
                Log.d(TcpIoLoopAppToVpnWorker.class.getName(),
                        "Receive fin packet, input ip packet = " +
                                inputIpPacket + ", tcp loop = " + this.tcpIoLoop);
                try {
                    this.outputDataQueue.put(finAckOutputData);
                } catch (InterruptedException e) {
                    Log.e(TcpIoLoopAppToVpnWorker.class.getName(),
                            "Fail to send FIN_ACK to app, input ip packet =" +
                                    inputIpPacket + ", tcp loop = " + this.tcpIoLoop);
                    continue;
                }
                if (targetChannel == null) {
                    this.tcpIoLoop.stop();
                    return;
                }
                targetChannel.close().syncUninterruptibly().addListener((ChannelFutureListener) future -> {
                    if (!future.isSuccess()) {
                        return;
                    }
                    TcpIoLoopVpntoAppData lastAckOutputData = new TcpIoLoopVpntoAppData();
                    lastAckOutputData.setCommand(TcpIoLoopVpnToAppCommand.DO_LAST_ACK);
                    try {
                        this.tcpIoLoop
                                .setVpnToAppAcknowledgementNumber(
                                        this.tcpIoLoop.getVpnToAppAcknowledgementNumber());
                        this.tcpIoLoop.setVpnToAppSequenceNumber(this.tcpIoLoop.getVpnToAppSequenceNumber());
                        this.outputDataQueue.put(lastAckOutputData);
                        this.tcpIoLoop.switchStatus(TcpIoLoopStatus.LAST_ACK);
                        Log.d(TcpIoLoopAppToVpnWorker.class.getName(),
                                "Receive fin packet and close target channel, input ip packet = " +
                                        inputIpPacket + ", tcp loop = " + this.tcpIoLoop);
                    } catch (InterruptedException e) {
                        Log.e(TcpIoLoopAppToVpnWorker.class.getName(),
                                "Fail to send FIN_ACK to app, input ip packet =" +
                                        inputIpPacket + ", tcp loop = " + this.tcpIoLoop);
                    }
                });
            }
            throw new IllegalStateException();
        }
    }
}
