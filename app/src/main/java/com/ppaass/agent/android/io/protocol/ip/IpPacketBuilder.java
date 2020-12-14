package com.ppaass.agent.android.io.protocol.ip;

import com.ppaass.agent.android.io.protocol.tcp.TcpPacket;
import com.ppaass.agent.android.io.protocol.udp.UdpPacket;

public class IpPacketBuilder {
    private IIpHeader header;
    private IIpData data;

    public IpPacketBuilder header(IIpHeader header) {
        this.header = header;
        return this;
    }

    public IpPacketBuilder data(IIpData data) {
        this.data = data;
        return this;
    }

    public IpPacket build() {
        if (this.header.getVersion() != IpHeaderVersion.V4) {
            throw new UnsupportedOperationException("Only support IP V4");
        }
        IpV4Header header = (IpV4Header) this.header;
        if (IpDataProtocol.TCP == header.getProtocol()) {
            TcpPacket tcpPacket = (TcpPacket) this.data;
            header.setTotalLength(header.getInternetHeaderLength() * 4 + tcpPacket.getHeader().getOffset() * 4 +
                    tcpPacket.getData().length);
            return new IpPacket(header, tcpPacket);
        }
        if (IpDataProtocol.UDP == header.getProtocol()) {
            UdpPacket udpPacket = (UdpPacket) this.data;
            header.setTotalLength(header.getInternetHeaderLength() * 4 + udpPacket.getHeader().getTotalLength());
            return new IpPacket(header, udpPacket);
        }
        throw new UnsupportedOperationException("Only support TCP or UDP");
    }
}