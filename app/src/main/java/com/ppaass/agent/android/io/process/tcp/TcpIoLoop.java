package com.ppaass.agent.android.io.process.tcp;

import com.ppaass.agent.android.io.process.IIoLoop;
import com.ppaass.agent.android.io.protocol.ip.*;
import com.ppaass.agent.android.io.protocol.tcp.TcpHeader;
import com.ppaass.agent.android.io.protocol.tcp.TcpPacket;

import java.net.InetAddress;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

public class TcpIoLoop implements IIoLoop {
    private static final int TIMEOUT_SECONDS = 20;
    private final BlockingDeque<IpPacket> ipPacketQueue;
    private final InetAddress sourceAddress;
    private final InetAddress destinationAddress;
    private final int sourcePort;
    private final int destinationPort;
    private final String key;
    private TcpLoopStatus status;

    public TcpIoLoop(InetAddress sourceAddress, InetAddress destinationAddress, int sourcePort, int destinationPort,
                     String key) {
        this.sourceAddress = sourceAddress;
        this.destinationAddress = destinationAddress;
        this.sourcePort = sourcePort;
        this.destinationPort = destinationPort;
        this.key = key;
        this.ipPacketQueue = new LinkedBlockingDeque<>();
        this.status = TcpLoopStatus.LISTEN;
    }

    @Override
    public String getKey() {
        return this.key;
    }

    @Override
    public void loop() {
        try {
            IpPacket packet = this.ipPacketQueue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (packet == null) {
                this.stop();
                return;
            }
            while (packet != null) {
                //Do some thing
                IIpHeader ipHeader = packet.getHeader();
                if (ipHeader.getVersion() != IpHeaderVersion.V4) {
                    this.stop();
                    return;
                }
                IpV4Header ipV4Header = (IpV4Header) ipHeader;
                if (ipV4Header.getProtocol() != IpDataProtocol.TCP) {
                    this.stop();
                    return;
                }
                if (!this.execute(ipV4Header, (TcpPacket) packet.getData())) {
                    this.stop();
                    return;
                }
                packet = this.ipPacketQueue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            this.stop();
        }
    }

    @Override
    public void push(IpPacket ipPacket) {
        this.ipPacketQueue.offer(ipPacket);
    }

    @Override
    public void stop() {
        this.ipPacketQueue.clear();
    }

    private boolean execute(IpV4Header ipV4Header, TcpPacket tcpPacket) {
        TcpHeader tcpHeader = tcpPacket.getHeader();
        if (tcpHeader.isSyn() && !tcpHeader.isAck()) {
            //Receive a syn.
            if (this.status == TcpLoopStatus.LISTEN) {
                //First syn
                this.status = TcpLoopStatus.SYN_RECEIVE;
            }
            if (this.status == TcpLoopStatus.ESTABLISHED) {
                //Send data
            }
            throw new UnsupportedOperationException("Do not support other.");
        }
        if (tcpHeader.isSyn() && tcpHeader.isAck()) {
            //Receive a syn + ack
            throw new UnsupportedOperationException("Do not support other.");
        }
        if (!tcpHeader.isSyn() && tcpHeader.isAck()) {
            //Receive a ack
            if (this.status == TcpLoopStatus.SYN_RECEIVE) {
            }
            throw new UnsupportedOperationException("Do not support other.");
        }
        throw new UnsupportedOperationException("Do not support other.");
    }
}
