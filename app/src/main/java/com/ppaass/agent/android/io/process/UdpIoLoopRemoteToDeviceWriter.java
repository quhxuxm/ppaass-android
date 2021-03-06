package com.ppaass.agent.android.io.process;

import com.ppaass.common.log.IPpaassLogger;
import com.ppaass.common.log.PpaassLoggerFactory;
import com.ppaass.protocol.base.ip.*;
import com.ppaass.protocol.base.udp.UdpPacket;
import com.ppaass.protocol.base.udp.UdpPacketBuilder;

import java.io.IOException;
import java.io.OutputStream;

class UdpIoLoopRemoteToDeviceWriter {
    private static final IPpaassLogger logger = PpaassLoggerFactory.INSTANCE.getLogger();
    public static final UdpIoLoopRemoteToDeviceWriter INSTANCE = new UdpIoLoopRemoteToDeviceWriter();

    private UdpIoLoopRemoteToDeviceWriter() {
    }

    public IpPacket buildUdpPacket(byte[] sourceAddress, int sourcePort,
                                   byte[] destinationAddress, int destinationPort, byte[] data) {
        UdpPacketBuilder udpPacketBuilder = new UdpPacketBuilder();
        udpPacketBuilder.sourcePort(sourcePort).destinationPort(destinationPort).data(data);
        return this.buildIpPacket(udpPacketBuilder, sourceAddress, destinationAddress);
    }

    private IpPacket buildIpPacket(UdpPacketBuilder udpPacketBuilder, byte[] sourceAddress,
                                   byte[] destinationAddress) {
        short identification = (short) (Math.random() * 10000);
        IpV4Header ipV4Header =
                new IpV4HeaderBuilder()
                        .destinationAddress(destinationAddress)
                        .sourceAddress(sourceAddress)
                        .protocol(IpDataProtocol.UDP).identification(identification).build();
        IpPacketBuilder ipPacketBuilder = new IpPacketBuilder();
        UdpPacket udpPacket = udpPacketBuilder.build();
        ipPacketBuilder.data(udpPacket);
        ipPacketBuilder.header(ipV4Header);
        return ipPacketBuilder.build();
    }

    public void writeIpPacketToDevice(IpPacket ipPacket,
                                      OutputStream remoteToDeviceStream) {
        try {
            logger
                    .debug(() -> "Write UDP packet to app.", () -> new Object[]{ipPacket});
            remoteToDeviceStream.write(IpPacketWriter.INSTANCE.write(ipPacket));
            remoteToDeviceStream.flush();
        } catch (IOException e) {
            logger
                    .error(() -> "Fail to write ip packet (UDP) to app because of exception.", () -> new Object[]{e});
        }
    }
}
