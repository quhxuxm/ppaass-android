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
    private Thread loopThread;
    private boolean alive;

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
        if (this.loopThread != null) {
            return;
        }
        this.loopThread = new Thread(() -> {
            try {
                IpPacket packet = TcpIoLoop.this.ipPacketQueue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (packet == null) {
                    TcpIoLoop.this.stop();
                    return;
                }
                while (packet != null && alive) {
                    //Do some thing
                    IIpHeader ipHeader = packet.getHeader();
                    if (ipHeader.getVersion() != IpHeaderVersion.V4) {
                        TcpIoLoop.this.stop();
                        return;
                    }
                    IpV4Header ipV4Header = (IpV4Header) ipHeader;
                    if (ipV4Header.getProtocol() != IpDataProtocol.TCP) {
                        TcpIoLoop.this.stop();
                        return;
                    }
                    if (!TcpIoLoop.this.execute(ipV4Header, (TcpPacket) packet.getData())) {
                        TcpIoLoop.this.stop();
                        return;
                    }
                    packet = TcpIoLoop.this.ipPacketQueue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                TcpIoLoop.this.stop();
            }
        });
        this.loopThread.start();
    }

    @Override
    public void offerIpPacket(IpPacket ipPacket) {
        this.ipPacketQueue.offer(ipPacket);
    }

    @Override
    public void stop() {
        this.ipPacketQueue.clear();
        this.alive = false;
    }

    private boolean execute(IpV4Header ipV4Header, TcpPacket tcpPacket) {
        TcpHeader tcpHeader = tcpPacket.getHeader();
        if (tcpHeader.isSyn() && !tcpHeader.isAck()) {
            //Receive a syn.
            if (this.status == TcpLoopStatus.LISTEN) {
                //First syn
                this.status = TcpLoopStatus.SYN_RECEIVE;
                return true;
            }
            if (this.status == TcpLoopStatus.ESTABLISHED) {
                //Send data
                return true;
            }
        }
        if (tcpHeader.isSyn() && tcpHeader.isAck()) {
            //Receive a syn + ack
            return true;
        }
        if (!tcpHeader.isSyn() && tcpHeader.isAck()) {
            //Receive a ack
            if (this.status == TcpLoopStatus.SYN_RECEIVE) {
                return true;
            }
        }
        return true;
    }
}
