package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import com.ppaass.agent.android.IPpaassConstant;
import com.ppaass.agent.android.io.protocol.ip.*;
import com.ppaass.agent.android.io.protocol.tcp.TcpHeader;
import com.ppaass.agent.android.io.protocol.tcp.TcpPacket;
import com.ppaass.kt.common.*;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;

import java.util.concurrent.BlockingDeque;
import java.util.concurrent.TimeUnit;

import static com.ppaass.agent.android.io.process.IIoConstant.TCP_LOOP;
import static com.ppaass.agent.android.io.process.IIoConstant.TIMEOUT_SECONDS;

class TcpIoLoopAppToVpnWorker implements Runnable {
    private final TcpIoLoop tcpIoLoop;
    private final Bootstrap proxyTcpBootstrap;
    private boolean alive;
    private Channel proxyChannel = null;
    private final BlockingDeque<IpPacket> inputIpPacketQueue;
    private final BlockingDeque<TcpIoLoopVpntoAppData> outputDataQueue;

    TcpIoLoopAppToVpnWorker(TcpIoLoop tcpIoLoop,
                            Bootstrap proxyTcpBootstrap,
                            BlockingDeque<IpPacket> inputIpPacketQueue,
                            BlockingDeque<TcpIoLoopVpntoAppData> outputDataQueue) {
        this.tcpIoLoop = tcpIoLoop;
        this.proxyTcpBootstrap = proxyTcpBootstrap;
        this.inputIpPacketQueue = inputIpPacketQueue;
        this.outputDataQueue = outputDataQueue;
        this.alive = false;
    }

    public void stop() {
        this.alive = false;
        this.inputIpPacketQueue.clear();
    }

    public void offerIpPacket(IpPacket ipPacket) {
        this.inputIpPacketQueue.offer(ipPacket);
    }

    @Override
    public void run() {
        try {
            while (alive) {
                IpPacket inputIpPacket = this.inputIpPacketQueue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (inputIpPacket == null) {
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
                        this.tcpIoLoop.setAppToVpnSequenceNumber(inputTcpHeader.getSequenceNumber());
                        this.tcpIoLoop.setAppToVpnSequenceNumber(inputTcpHeader.getAcknowledgementNumber());
                        tcpIoLoop.setVpnToAppAcknowledgementNumber(tcpIoLoop.getAppToVpnSequenceNumber() + 1);
                        //Just make sure it is a increase.
                        tcpIoLoop.setVpnToAppSequenceNumber(tcpIoLoop.getVpnToAppSequenceNumber() + 1);
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
                        this.tcpIoLoop.setAppToVpnSequenceNumber(inputTcpHeader.getSequenceNumber());
                        this.tcpIoLoop.setAppToVpnSequenceNumber(inputTcpHeader.getAcknowledgementNumber());
                        this.tcpIoLoop.setVpnToAppAcknowledgementNumber(
                                this.tcpIoLoop.getAppToVpnSequenceNumber() + 1 + inputTcpPacket.getData().length);
                        this.proxyChannel.writeAndFlush(agentMessage).syncUninterruptibly();
                        continue;
                    }
                }
                if (!inputTcpHeader.isSyn() && inputTcpHeader.isAck()) {
                    //Receive a ack
                    if (this.tcpIoLoop.getStatus() == TcpIoLoopStatus.SYN_RECEIVED) {
                        if (inputTcpHeader.getAcknowledgementNumber() != tcpIoLoop.getVpnToAppSequenceNumber() + 1) {
                            Log.e(TcpIoLoopAppToVpnWorker.class.getName(), "The ack from app is not correct.");
                            continue;
                        }
                        this.tcpIoLoop.switchStatus(TcpIoLoopStatus.ESTABLISHED);
                        continue;
                    }
                    if (this.tcpIoLoop.getStatus() == TcpIoLoopStatus.ESTABLISHED) {
                        this.tcpIoLoop.setVpnToAppSequenceNumber(inputTcpHeader.getAcknowledgementNumber());
                    }
                }
            }
        } catch (InterruptedException e) {
            this.stop();
        }
    }
}
