package com.ppaass.agent.android.io.protocol.tcp;

import com.ppaass.agent.android.io.protocol.IProtocolConst;

public class TcpPacketBuilder {
    private int sourcePort;
    private int destinationPort;
    private long sequenceNumber;
    private long acknowledgementNumber;
    private int resolve;
    private boolean urg;
    private boolean ack;
    private boolean psh;
    private boolean rst;
    private boolean syn;
    private boolean fin;
    private int window;
    private int urgPointer;
    private byte[] optionAndPadding;
    private byte[] data;
    private int checksum;

    public TcpPacketBuilder() {
        this.optionAndPadding = new byte[]{};
        this.data = new byte[]{};
        this.window = 0;
        this.urgPointer = 0;
        this.resolve = 0;
    }

    public TcpPacketBuilder sourcePort(int sourcePort) {
        this.sourcePort = sourcePort;
        return this;
    }

    public TcpPacketBuilder destinationPort(int destinationPort) {
        this.destinationPort = destinationPort;
        return this;
    }

    public TcpPacketBuilder sequenceNumber(long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
        return this;
    }

    public TcpPacketBuilder acknowledgementNumber(long acknowledgementNumber) {
        this.acknowledgementNumber = acknowledgementNumber;
        return this;
    }

    public TcpPacketBuilder resolve(int resolve) {
        this.resolve = resolve;
        return this;
    }

    public TcpPacketBuilder urg(boolean urg) {
        this.urg = urg;
        return this;
    }

    public TcpPacketBuilder ack(boolean ack) {
        this.ack = ack;
        return this;
    }

    public TcpPacketBuilder psh(boolean psh) {
        this.psh = psh;
        return this;
    }

    public TcpPacketBuilder rst(boolean rst) {
        this.rst = rst;
        return this;
    }

    public TcpPacketBuilder syn(boolean syn) {
        this.syn = syn;
        return this;
    }

    public TcpPacketBuilder fin(boolean fin) {
        this.fin = fin;
        return this;
    }

    public TcpPacketBuilder window(int window) {
        this.window = window;
        return this;
    }

    public TcpPacketBuilder urgPointer(int urgPointer) {
        this.urgPointer = urgPointer;
        return this;
    }

    public TcpPacketBuilder optionAndPadding(byte[] optionAndPadding) {
        if (optionAndPadding.length % 4 != 0) {
            throw new IllegalArgumentException("Option and padding is illegal.");
        }
        this.optionAndPadding = optionAndPadding;
        return this;
    }

    TcpPacketBuilder checksum(int checksum) {
        this.checksum = checksum;
        return this;
    }

    public TcpPacketBuilder data(byte[] data) {
        if (data == null) {
            this.data = new byte[]{};
            return this;
        }
        this.data = data;
        return this;
    }

    public TcpPacket build() {
        TcpHeader tcpHeader = new TcpHeader();
        tcpHeader.setSourcePort(this.sourcePort);
        tcpHeader.setDestinationPort(this.destinationPort);
        tcpHeader.setSequenceNumber(this.sequenceNumber);
        tcpHeader.setAcknowledgementNumber(this.acknowledgementNumber);
        tcpHeader.setAck(this.ack);
        tcpHeader.setSyn(this.syn);
        tcpHeader.setFin(this.fin);
        tcpHeader.setPsh(this.psh);
        tcpHeader.setRst(this.rst);
        tcpHeader.setUrg(this.urg);
        tcpHeader.setOptionAndPadding(this.optionAndPadding);
        int offset = (this.optionAndPadding.length + IProtocolConst.MIN_TCP_HEADER_LENGTH) / 4;
        tcpHeader.setOffset(offset);
        tcpHeader.setResolve(this.resolve);
        tcpHeader.setUrgPointer(this.urgPointer);
        tcpHeader.setWindow(this.window);
        tcpHeader.setChecksum(this.checksum);
        return new TcpPacket(tcpHeader, this.data);
    }
}

