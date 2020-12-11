package com.ppaass.agent.android.io.protocol.tcp;

import com.ppaass.agent.android.io.protocol.ip.IIpData;

import java.util.Arrays;

public class TcpPacket implements IIpData {
    private final TcpHeader header;
    private final byte[] data;

    public TcpPacket(TcpHeader header, byte[] data) {
        this.header = header;
        this.data = data;
    }

    public TcpHeader getHeader() {
        return header;
    }

    public byte[] getData() {
        return data;
    }

    @Override
    public String toString() {
        return "TcpPacket{" +
                "header=" + header +
                ", data=" + Arrays.toString(data) +
                '}';
    }
}
