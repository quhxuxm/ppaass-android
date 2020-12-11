package com.ppaass.agent.android.service;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import com.ppaass.agent.android.R;
import com.ppaass.agent.android.io.process.IIoLoop;
import com.ppaass.agent.android.io.process.IoLoopHolder;
import com.ppaass.agent.android.io.process.tcp.TcpIoLoop;
import com.ppaass.agent.android.io.process.udp.UdpIoLoop;
import com.ppaass.agent.android.io.protocol.IpPacketReader;
import com.ppaass.agent.android.io.protocol.ip.IpDataProtocol;
import com.ppaass.agent.android.io.protocol.ip.IpHeaderVersion;
import com.ppaass.agent.android.io.protocol.ip.IpPacket;
import com.ppaass.agent.android.io.protocol.ip.IpV4Header;
import com.ppaass.agent.android.io.protocol.tcp.TcpPacket;
import com.ppaass.agent.android.io.protocol.udp.UdpPacket;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;

import static com.ppaass.agent.android.IPpaassConstant.VPN_ADDRESS;
import static com.ppaass.agent.android.IPpaassConstant.VPN_ROUTE;

public class PpaassVpnService extends VpnService {
    private static final int VPN_BUFFER_SIZE = 32768;

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        Thread vpnThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Builder vpnBuilder = new Builder();
                vpnBuilder.addAddress(VPN_ADDRESS, 32);
                vpnBuilder.addRoute(VPN_ROUTE, 0);
                ParcelFileDescriptor vpnInterface = vpnBuilder.setSession(getString(R.string.app_name)).establish();
                final FileDescriptor vpnFileDescriptor = vpnInterface.getFileDescriptor();
                final FileInputStream vpnInputStream = new FileInputStream(vpnFileDescriptor);
                FileOutputStream vpnOutputStream = new FileOutputStream(vpnFileDescriptor);
                while (true) {
                    byte[] buffer = new byte[VPN_BUFFER_SIZE];
                    try {
                        int readResult = vpnInputStream.read(buffer);
                        if (readResult <= 0) {
                            continue;
                        }
                        IpPacket ipPacket = IpPacketReader.INSTANCE.parse(buffer);
                        if (IpHeaderVersion.V4 != ipPacket.getHeader().getVersion()) {
                            continue;
                        }
                        IpV4Header ipV4Header = (IpV4Header) ipPacket.getHeader();
                        IpDataProtocol protocol = ipV4Header.getProtocol();
                        if (IpDataProtocol.TCP == protocol) {
                            TcpPacket tcpPacket = (TcpPacket) ipPacket.getData();
                            final InetAddress sourceAddress = InetAddress.getByAddress(ipV4Header.getSourceAddress());
                            final int sourcePort = tcpPacket.getHeader().getSourcePort();
                            final InetAddress destinationAddress =
                                    InetAddress.getByAddress(ipV4Header.getDestinationAddress());
                            final int destinationPort = tcpPacket.getHeader().getDestinationPort();
                            final String ioLoopKey = IoLoopHolder.INSTANCE
                                    .generateLoopKey(sourceAddress, sourcePort
                                            , destinationAddress, destinationPort
                                    );
                            IoLoopHolder.INSTANCE.getIoLoops().computeIfAbsent(ioLoopKey,
                                    (key) -> {
                                        TcpIoLoop result = new TcpIoLoop(sourceAddress, destinationAddress, sourcePort,
                                                destinationPort, key);
                                        result.loop();
                                        return result;
                                    });
                            IIoLoop ioLoop = IoLoopHolder.INSTANCE.getIoLoops().get(ioLoopKey);
                            if (ioLoop == null) {
                                throw new RuntimeException();
                            }
                            ioLoop.offerIpPacket(ipPacket);
                            continue;
                        }
                        if (IpDataProtocol.UDP == protocol) {
                            UdpPacket udpPacket = (UdpPacket) ipPacket.getData();
                            final InetAddress sourceAddress = InetAddress.getByAddress(ipV4Header.getSourceAddress());
                            final int sourcePort = udpPacket.getHeader().getSourcePort();
                            final InetAddress destinationAddress =
                                    InetAddress.getByAddress(ipV4Header.getDestinationAddress());
                            final int destinationPort = udpPacket.getHeader().getDestinationPort();
                            final String ioLoopKey = IoLoopHolder.INSTANCE
                                    .generateLoopKey(sourceAddress, sourcePort
                                            , destinationAddress, destinationPort
                                    );
                            IoLoopHolder.INSTANCE.getIoLoops().computeIfAbsent(ioLoopKey,
                                    (key) -> {
                                        UdpIoLoop result = new UdpIoLoop(sourceAddress, destinationAddress, sourcePort,
                                                destinationPort, key);
                                        result.loop();
                                        return result;
                                    });
                            IIoLoop ioLoop = IoLoopHolder.INSTANCE.getIoLoops().get(ioLoopKey);
                            if (ioLoop == null) {
                                throw new RuntimeException();
                            }
                            ioLoop.offerIpPacket(ipPacket);
                            continue;
                        }
                        throw new UnsupportedOperationException();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        });
        vpnThread.start();
        return START_STICKY;
    }
}
