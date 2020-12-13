package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import com.ppaass.agent.android.io.protocol.ip.*;
import com.ppaass.agent.android.io.protocol.tcp.TcpPacketBuilder;

import java.io.FileOutputStream;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.TimeUnit;

import static com.ppaass.agent.android.io.process.IIoConstant.TIMEOUT_SECONDS;

class TcpIoLoopVpnToAppWorker implements Runnable {
    private final TcpIoLoop tcpIoLoop;
    private final BlockingDeque<TcpIoLoopVpntoAppData> outputDataQueue;
    private boolean alive;
    private final FileOutputStream vpnOutputStream;

    TcpIoLoopVpnToAppWorker(TcpIoLoop tcpIoLoop,
                            BlockingDeque<TcpIoLoopVpntoAppData> outputDataQueue,
                            FileOutputStream vpnOutputStream) {
        this.tcpIoLoop = tcpIoLoop;
        this.outputDataQueue = outputDataQueue;
        this.vpnOutputStream = vpnOutputStream;
        this.alive = false;
    }

    public void stop() {
        this.alive = false;
    }

    public void offerOutputData(TcpIoLoopVpntoAppData outputData) {
        this.outputDataQueue.offer(outputData);
    }

    @Override
    public void run() {
        try {
            while (this.alive) {
                TcpIoLoopVpntoAppData outputData = this.outputDataQueue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (outputData == null) {
                    this.stop();
                    return;
                }
                IpV4Header ipV4Header =
                        new IpV4HeaderBuilder()
                                .destinationAddress(tcpIoLoop.getSourceAddress().getAddress())
                                .sourceAddress(tcpIoLoop.getDestinationAddress().getAddress())
                                .protocol(IpDataProtocol.TCP).build();
                TcpPacketBuilder tcpPacketBuilder = new TcpPacketBuilder()
                        .sequenceNumber(tcpIoLoop.getVpnToAppSequenceNumber())
                        .acknowledgementNumber(tcpIoLoop.getVpnToAppAcknowledgementNumber())
                        .destinationPort(tcpIoLoop.getSourcePort())
                        .sourcePort(tcpIoLoop.getDestinationPort());
                IpPacketBuilder ipPacketBuilder = new IpPacketBuilder().header(ipV4Header);
                switch (outputData.getCommand()) {
                    case ACK: {
                        tcpPacketBuilder.ack(true);
                        break;
                    }
                    case SYN: {
                        tcpPacketBuilder.syn(true);
                        break;
                    }
                    case SYN_ACK: {
                        tcpPacketBuilder.ack(true).syn(true);
                        break;
                    }
                }
                ipPacketBuilder.data(tcpPacketBuilder.build());
                IpPacket ipPacket = ipPacketBuilder.build();
                this.vpnOutputStream.write(IpPacketWriter.INSTANCE.write(ipPacket));
                this.vpnOutputStream.flush();
            }
        } catch (Exception e) {
            Log.e(TcpIoLoopVpnToAppWorker.class.getName(), "Fail to write output ip package to application.");
        }
    }
}
