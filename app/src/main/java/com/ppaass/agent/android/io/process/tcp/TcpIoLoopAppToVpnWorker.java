package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import com.ppaass.agent.android.IPpaassConstant;
import com.ppaass.agent.android.io.process.IoLoopHolder;
import com.ppaass.agent.android.io.protocol.ip.*;
import com.ppaass.agent.android.io.protocol.tcp.TcpHeader;
import com.ppaass.agent.android.io.protocol.tcp.TcpPacket;
import com.ppaass.kt.common.*;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;

import java.util.concurrent.BlockingDeque;

import static com.ppaass.agent.android.io.process.IIoConstant.TCP_LOOP;

class TcpIoLoopAppToVpnWorker implements Runnable {
    private final TcpIoLoop tcpIoLoop;
    private final Bootstrap proxyTcpBootstrap;
    private boolean alive;
    private Channel proxyChannel = null;
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
            if ((inputTcpHeader.getSequenceNumber() == this.tcpIoLoop.getAppToVpnSequenceNumber() &&
                    inputTcpHeader.getAcknowledgementNumber() == this.tcpIoLoop.getAppToVpnAcknowledgementNumber()) &&
                    this.tcpIoLoop.getStatus() != TcpIoLoopStatus.ESTABLISHED) {
                Log.d(TcpIoLoopAppToVpnWorker.class.getName(),
                        "Ignore duplicate tcp packet, input ip packet = " + inputIpPacket + ", tcp loop = " +
                                this.tcpIoLoop);
                continue;
            }
            this.tcpIoLoop.setAppToVpnSequenceNumber(inputTcpHeader.getSequenceNumber());
            this.tcpIoLoop.setAppToVpnAcknowledgementNumber(inputTcpHeader.getAcknowledgementNumber());
            Log.d(TcpIoLoopAppToVpnWorker.class.getName(),
                    "Receive tcp packet, input ip packet = " + inputIpPacket + ", tcp loop = " +
                            this.tcpIoLoop);
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
                    this.proxyChannel =
                            this.proxyTcpBootstrap
                                    .connect(IPpaassConstant.PROXY_SERVER_ADDRESS,
                                            IPpaassConstant.PROXY_SERVER_PORT)
                                    .syncUninterruptibly().channel();
                    this.proxyChannel.attr(TCP_LOOP).setIfAbsent(this.tcpIoLoop);
                    this.tcpIoLoop.setVpnToAppAcknowledgementNumber(inputTcpHeader.getSequenceNumber() + 1);
                    this.tcpIoLoop.setVpnToAppSequenceNumber(inputTcpHeader.getAcknowledgementNumber());
                    MessageBody<AgentMessageBodyType> agentMessageBody = new MessageBody<>(
                            SerializerKt.generateUuid(), IPpaassConstant.USER_TOKEN,
                            this.tcpIoLoop.getDestinationAddress().getHostAddress(),
                            this.tcpIoLoop.getDestinationPort(),
                            AgentMessageBodyType.CONNECT_WITH_KEEP_ALIVE, new byte[]{}
                    );
                    Message<AgentMessageBodyType> agentMessage = new Message<>(SerializerKt.generateUuidInBytes(),
                            EncryptionType.Companion.choose(), agentMessageBody);
                    proxyChannel.writeAndFlush(agentMessage).syncUninterruptibly();
                    continue;
                }
                if (this.tcpIoLoop.getStatus() == TcpIoLoopStatus.ESTABLISHED) {
                    //Send data
                    if (this.proxyChannel == null) {
                        Log.e(TcpIoLoopAppToVpnWorker.class.getName(), "The proxy channel is null.");
                        throw new IllegalStateException("The proxy channel is null.");
                    }
                    MessageBody<AgentMessageBodyType> agentMessageBody = new MessageBody<>(
                            SerializerKt.generateUuid(), IPpaassConstant.USER_TOKEN,
                            this.tcpIoLoop.getDestinationAddress().getHostAddress(),
                            this.tcpIoLoop.getDestinationPort(),
                            AgentMessageBodyType.TCP_DATA, inputTcpPacket.getData()
                    );
                    Message<AgentMessageBodyType> agentMessage = new Message<>(SerializerKt.generateUuidInBytes(),
                            EncryptionType.Companion.choose(), agentMessageBody);
                    this.tcpIoLoop.setVpnToAppAcknowledgementNumber(
                            inputTcpHeader.getSequenceNumber() + 1 + inputTcpPacket.getData().length);
                    this.tcpIoLoop.setVpnToAppSequenceNumber(inputTcpHeader.getAcknowledgementNumber());
                    this.proxyChannel.writeAndFlush(agentMessage).syncUninterruptibly();
                    continue;
                }
            }
            if (!inputTcpHeader.isSyn() && inputTcpHeader.isAck()) {
                //ACK for SYN ACK
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
                    this.tcpIoLoop.setVpnToAppSequenceNumber(inputTcpHeader.getAcknowledgementNumber());
                    //PSH ACK
                    if (inputTcpPacket.getData().length > 0) {
                        MessageBody<AgentMessageBodyType> agentMessageBody = new MessageBody<>(
                                SerializerKt.generateUuid(), IPpaassConstant.USER_TOKEN,
                                this.tcpIoLoop.getDestinationAddress().getHostAddress(),
                                this.tcpIoLoop.getDestinationPort(),
                                AgentMessageBodyType.TCP_DATA, inputTcpPacket.getData()
                        );
                        Message<AgentMessageBodyType> agentMessage = new Message<>(SerializerKt.generateUuidInBytes(),
                                EncryptionType.Companion.choose(), agentMessageBody);
                        this.tcpIoLoop.setVpnToAppAcknowledgementNumber(
                                inputTcpHeader.getSequenceNumber() + 1 + inputTcpPacket.getData().length);
                        TcpIoLoopVpntoAppData ackData = new TcpIoLoopVpntoAppData();
                        ackData.setCommand(TcpIoLoopVpnToAppCommand.DO_ACK);
                        Log.d(TcpIoLoopAppToVpnWorker.class.getName(),
                                "There is data psh ack packet, write it to proxy, input ip packet = " +
                                        inputIpPacket + ", tcp loop = " + this.tcpIoLoop + ", agent message = " +
                                        agentMessage);
                        proxyChannel.writeAndFlush(agentMessage).syncUninterruptibly();
                    }
                    Log.d(TcpIoLoopAppToVpnWorker.class.getName(),
                            "Receive push, input ip packet =" + inputIpPacket + ",tcp loop = " + this.tcpIoLoop);
                    continue;
                }
//                if (this.tcpIoLoop.getStatus() == TcpIoLoopStatus.ESTABLISHED) {
//                    this.tcpIoLoop.setVpnToAppSequenceNumber(inputTcpHeader.getAcknowledgementNumber());
//                    if (inputTcpPacket.getData().length > 0) {
//                        MessageBody<AgentMessageBodyType> agentMessageBody = new MessageBody<>(
//                                SerializerKt.generateUuid(), IPpaassConstant.USER_TOKEN,
//                                this.tcpIoLoop.getDestinationAddress().getHostAddress(),
//                                this.tcpIoLoop.getDestinationPort(),
//                                AgentMessageBodyType.TCP_DATA, inputTcpPacket.getData()
//                        );
//                        Message<AgentMessageBodyType> agentMessage = new Message<>(SerializerKt.generateUuidInBytes(),
//                                EncryptionType.Companion.choose(), agentMessageBody);
//                        this.tcpIoLoop.setVpnToAppAcknowledgementNumber(
//                                inputTcpHeader.getSequenceNumber() + 1 + inputTcpPacket.getData().length);
//                        TcpIoLoopVpntoAppData ackData = new TcpIoLoopVpntoAppData();
//                        ackData.setCommand(TcpIoLoopVpnToAppCommand.DO_ACK);
//                        Log.d(TcpIoLoopAppToVpnWorker.class.getName(),
//                                "There is data in ack packet when do hand shake (2), write it to proxy, input ip packet = " +
//                                        inputIpPacket + ", tcp loop = " + this.tcpIoLoop + ", agent message = " +
//                                        agentMessage);
//                        proxyChannel.writeAndFlush(agentMessage).syncUninterruptibly();
//                    }
//                    Log.d(TcpIoLoopAppToVpnWorker.class.getName(),
//                            "Tcp loop ESTABLISHED already, input ip packet =" + inputIpPacket + ", tcp loop = " +
//                                    this.tcpIoLoop);
//                    continue;
//                }
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
            }
            if (inputTcpHeader.isFin()) {
                if (this.proxyChannel == null) {
                    Log.e(TcpIoLoopAppToVpnWorker.class.getName(), "The proxy channel is null.");
                    throw new IllegalStateException("The proxy channel is null.");
                }
                TcpIoLoopVpntoAppData finAckOutputData = new TcpIoLoopVpntoAppData();
                finAckOutputData.setCommand(TcpIoLoopVpnToAppCommand.DO_FIN_ACK);
                this.tcpIoLoop.setVpnToAppAcknowledgementNumber(inputTcpHeader.getSequenceNumber() + 1);
                this.tcpIoLoop.setVpnToAppSequenceNumber(inputTcpHeader.getAcknowledgementNumber());
                this.tcpIoLoop.switchStatus(TcpIoLoopStatus.CLOSE_WAIT);
                try {
                    this.outputDataQueue.put(finAckOutputData);
                } catch (InterruptedException e) {
                    Log.e(TcpIoLoopAppToVpnWorker.class.getName(),
                            "Fail to send FIN_ACK to app, input ip packet =" +
                                    inputIpPacket + ", tcp loop = " + this.tcpIoLoop);
                    continue;
                }
                proxyChannel.close().syncUninterruptibly().addListener((ChannelFutureListener) future -> {
                    if (!future.isSuccess()) {
                        return;
                    }
                    TcpIoLoopVpntoAppData lastAckOutputData = new TcpIoLoopVpntoAppData();
                    lastAckOutputData.setCommand(TcpIoLoopVpnToAppCommand.DO_LAST_ACK);
                    try {
                        this.tcpIoLoop.setVpnToAppSequenceNumber(this.tcpIoLoop.getVpnToAppSequenceNumber());
                        this.tcpIoLoop
                                .setVpnToAppAcknowledgementNumber(this.tcpIoLoop.getVpnToAppAcknowledgementNumber());
                        this.outputDataQueue.put(lastAckOutputData);
                        this.tcpIoLoop.switchStatus(TcpIoLoopStatus.LAST_ACK);
                    } catch (InterruptedException e) {
                        Log.e(TcpIoLoopAppToVpnWorker.class.getName(),
                                "Fail to send FIN_ACK to app, input ip packet =" +
                                        inputIpPacket + ", tcp loop = " + this.tcpIoLoop);
                    }
                });
            }
        }
    }
}
