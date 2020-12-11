package com.ppaass.agent.android.io.protocol.udp;

import com.ppaass.agent.android.io.protocol.ip.IIpData;

import java.util.Arrays;

public class UdpPacket implements IIpData {
    private final UdpHeader header;
    private final byte[] data;

    public UdpPacket(UdpHeader header, byte[] data) {
        this.header = header;
        this.data = data;
    }

    public byte[] getData() {
        return data;
    }

    public UdpHeader getHeader() {
        return header;
    }

    @Override
    public String toString() {
        return "UdpPacket{" +
                "header=" + header +
                ", data=" + Arrays.toString(data) +
                '}';
    }
}
