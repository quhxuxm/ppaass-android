package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import com.ppaass.agent.android.io.protocol.ip.*;
import com.ppaass.agent.android.io.protocol.tcp.TcpPacket;
import com.ppaass.agent.android.io.protocol.tcp.TcpPacketBuilder;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.BlockingDeque;

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

    public void start() {
        this.alive = true;
    }

    public void stop() {
        this.alive = false;
        this.outputDataQueue.clear();
    }

    public void offerOutputData(TcpIoLoopVpntoAppData outputData) {
        try {
            this.outputDataQueue.put(outputData);
        } catch (InterruptedException e) {
            Log.e(TcpIoLoopVpnToAppWorker.class.getName(),
                    "Fail to put output data to the queue, tcp loop = " + this.tcpIoLoop);
        }
    }

    @Override
    public void run() {
        while (this.alive) {
            TcpIoLoopVpntoAppData outputData = null;
            try {
                outputData = this.outputDataQueue.take();
            } catch (InterruptedException e) {
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
                    .sourcePort(tcpIoLoop.getDestinationPort()).window(65535);
            IpPacketBuilder ipPacketBuilder = new IpPacketBuilder().header(ipV4Header);
            switch (outputData.getCommand()) {
                case DO_ACK: {
                    tcpPacketBuilder.ack(true);
                    break;
                }
                case DO_SYN: {
                    tcpPacketBuilder.syn(true);
                    break;
                }
                case DO_PSH_ACK: {
                    tcpPacketBuilder.psh(true).ack(true);
                    break;
                }
                case DO_SYN_ACK: {
                    tcpPacketBuilder.ack(true).syn(true);
                    break;
                }
                case DO_FIN_ACK: {
                    tcpPacketBuilder.ack(true);
                    break;
                }
                case DO_LAST_ACK: {
                    tcpPacketBuilder.fin(true);
                    break;
                }
            }
            TcpPacket tcpPacket = tcpPacketBuilder.build();
            ipPacketBuilder.data(tcpPacket);
            IpPacket ipPacket = ipPacketBuilder.build();
            try {
                byte[] ipPacketBytes = IpPacketWriter.INSTANCE.write(ipPacket);
                Log.d(TcpIoLoopVpnToAppWorker.class.getName(),
                        "Write ip packet from VPN to APP, write tcp packet = " + tcpPacket + ", tcp loop = " +
                                this.tcpIoLoop);
                this.vpnOutputStream.write(ipPacketBytes);
                this.vpnOutputStream.flush();
            } catch (IOException e) {
                Log.e(TcpIoLoopVpnToAppWorker.class.getName(), "Fail to write ip packet to APP because of exception.",
                        e);
                this.stop();
                return;
            }
        }
    }
}
