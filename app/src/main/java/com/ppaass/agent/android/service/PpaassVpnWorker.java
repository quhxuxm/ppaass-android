package com.ppaass.agent.android.service;

import android.net.VpnService;
import android.util.Log;
import com.ppaass.agent.android.io.process.tcp.TcpIoLoopFlowProcessor;
import com.ppaass.agent.android.io.process.udp.UdpIoLoopFlowProcessor;
import com.ppaass.protocol.base.ip.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PpaassVpnWorker implements Runnable {
    private static final int DEVICE_TO_REMOTE_BUFFER_SIZE = 32768;
    private final ExecutorService executor;
    private final InputStream deviceToRemoteStream;
    private final TcpIoLoopFlowProcessor tcpIoLoopFlowProcessor;
    private final UdpIoLoopFlowProcessor udpIoLoopFlowProcessor;
    private boolean alive;

    public PpaassVpnWorker(InputStream deviceToRemoteStream, OutputStream remoteToDeviceStream,
                           VpnService vpnService, byte[] agentPrivateKeyBytes, byte[] proxyPublicKeyBytes) {
        this.deviceToRemoteStream = deviceToRemoteStream;
        this.alive = false;
        this.executor = Executors.newSingleThreadExecutor();
        this.tcpIoLoopFlowProcessor = new TcpIoLoopFlowProcessor(vpnService, remoteToDeviceStream, agentPrivateKeyBytes,
                proxyPublicKeyBytes);
        this.udpIoLoopFlowProcessor =
                new UdpIoLoopFlowProcessor(vpnService, remoteToDeviceStream, agentPrivateKeyBytes, proxyPublicKeyBytes);
    }

    public synchronized void start() {
        if (this.alive) {
            return;
        }
        this.alive = true;
        this.executor.execute(this);
    }

    public synchronized void stop() {
        this.alive = false;
        this.tcpIoLoopFlowProcessor.shutdown();
        this.executor.shutdown();
    }

    @Override
    public void run() {
        while (this.alive) {
            byte[] buffer = new byte[DEVICE_TO_REMOTE_BUFFER_SIZE];
            try {
                int readResult = this.deviceToRemoteStream.read(buffer);
                if (readResult < 0) {
                    continue;
                }
                IpPacket ipPacket = IpPacketReader.INSTANCE.parse(buffer);
                if (IpHeaderVersion.V4 != ipPacket.getHeader().getVersion()) {
                    Log.e(PpaassVpnService.class.getName(), "Ignore non-ipv4 packet.");
                    continue;
                }
                IpV4Header ipV4Header = (IpV4Header) ipPacket.getHeader();
                IpDataProtocol protocol = ipV4Header.getProtocol();
                if (IpDataProtocol.TCP == protocol) {
                    this.tcpIoLoopFlowProcessor.execute(ipPacket);
                    continue;
                }
                if (IpDataProtocol.UDP == protocol) {
                    this.udpIoLoopFlowProcessor.execute(ipPacket);
                    continue;
                }
                Log.e(PpaassVpnService.class.getName(), "Do not support other protocol, protocol = " + protocol);
            } catch (IOException e) {
                Log.e(PpaassVpnService.class.getName(), "Vpn service have exception", e);
            }
        }
    }
}
