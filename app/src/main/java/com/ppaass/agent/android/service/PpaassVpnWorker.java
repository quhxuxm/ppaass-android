package com.ppaass.agent.android.service;

import android.net.VpnService;
import android.util.Log;
import com.ppaass.agent.android.io.process.tcp.TcpIoLoopProcessor;
import com.ppaass.agent.android.io.protocol.ip.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PpaassVpnWorker implements Runnable {
    private static final int DEVICE_TO_REMOTE_BUFFER_SIZE = 32768;
    private final ExecutorService executor;
    private final InputStream deviceToRemoteStream;
    private final OutputStream remoteToDeviceStream;
    private final VpnService vpnService;
    private final byte[] agentPrivateKeyBytes;
    private final byte[] proxyPublicKeyBytes;
    private final TcpIoLoopProcessor tcpIoLoopProcessor;
    private boolean alive;

    public PpaassVpnWorker(InputStream deviceToRemoteStream, OutputStream remoteToDeviceStream,
                           VpnService vpnService, byte[] agentPrivateKeyBytes, byte[] proxyPublicKeyBytes) {
        this.deviceToRemoteStream = deviceToRemoteStream;
        this.remoteToDeviceStream = remoteToDeviceStream;
        this.vpnService = vpnService;
        this.agentPrivateKeyBytes = agentPrivateKeyBytes;
        this.proxyPublicKeyBytes = proxyPublicKeyBytes;
        this.alive = false;
        this.executor = Executors.newSingleThreadExecutor();
        this.tcpIoLoopProcessor = new TcpIoLoopProcessor(this.vpnService, agentPrivateKeyBytes, proxyPublicKeyBytes,
                remoteToDeviceStream);
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
        this.executor.shutdown();
    }

    @Override
    public void run() {
        while (this.alive) {
            byte[] buffer = new byte[DEVICE_TO_REMOTE_BUFFER_SIZE];
            try {
                int readResult = this.deviceToRemoteStream.read(buffer);
                if (readResult <= 0) {
                    Thread.sleep(50);
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
                    this.tcpIoLoopProcessor.process(ipPacket);
                    continue;
                }
                Log.e(PpaassVpnService.class.getName(), "Do not support other protocol, protocol = " + protocol);
            } catch (IOException | InterruptedException e) {
                Log.e(PpaassVpnService.class.getName(), "Vpn service have exception", e);
            }
        }
    }
}
