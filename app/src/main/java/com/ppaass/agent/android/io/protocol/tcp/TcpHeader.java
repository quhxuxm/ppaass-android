package com.ppaass.agent.android.io.protocol.tcp;

import java.util.Arrays;

public class TcpHeader {
    private int sourcePort;
    private int destinationPort;
    private long sequenceNumber;
    private long acknowledgementNumber;
    private int offset;
    private int resolve;
    private boolean urg;
    private boolean ack;
    private boolean psh;
    private boolean rst;
    private boolean syn;
    private boolean fin;
    private int window;
    private int checksum;
    private int urgPointer;
    private byte[] optionAndPadding;

    public int getSourcePort() {
        return sourcePort;
    }

    public void setSourcePort(int sourcePort) {
        this.sourcePort = sourcePort;
    }

    public int getDestinationPort() {
        return destinationPort;
    }

    public void setDestinationPort(int destinationPort) {
        this.destinationPort = destinationPort;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public long getAcknowledgementNumber() {
        return acknowledgementNumber;
    }

    public void setAcknowledgementNumber(long acknowledgementNumber) {
        this.acknowledgementNumber = acknowledgementNumber;
    }

    public int getOffset() {
        return offset;
    }

    public void setOffset(int offset) {
        this.offset = offset;
    }

    public int getResolve() {
        return resolve;
    }

    public void setResolve(int resolve) {
        this.resolve = resolve;
    }

    public boolean isUrg() {
        return urg;
    }

    public void setUrg(boolean urg) {
        this.urg = urg;
    }

    public boolean isAck() {
        return ack;
    }

    public void setAck(boolean ack) {
        this.ack = ack;
    }

    public boolean isPsh() {
        return psh;
    }

    public void setPsh(boolean psh) {
        this.psh = psh;
    }

    public boolean isRst() {
        return rst;
    }

    public void setRst(boolean rst) {
        this.rst = rst;
    }

    public boolean isSyn() {
        return syn;
    }

    public void setSyn(boolean syn) {
        this.syn = syn;
    }

    public boolean isFin() {
        return fin;
    }

    public void setFin(boolean fin) {
        this.fin = fin;
    }

    public int getWindow() {
        return window;
    }

    public void setWindow(int window) {
        this.window = window;
    }

    public int getChecksum() {
        return checksum;
    }

    public void setChecksum(int checksum) {
        this.checksum = checksum;
    }

    public void setUrgPointer(int urgPointer) {
        this.urgPointer = urgPointer;
    }

    public int getUrgPointer() {
        return urgPointer;
    }

    public byte[] getOptionAndPadding() {
        return optionAndPadding;
    }

    public void setOptionAndPadding(byte[] optionAndPadding) {
        this.optionAndPadding = optionAndPadding;
    }

    @Override
    public String toString() {
        return "TcpHeader{" +
                "sourcePort=" + sourcePort +
                ", destinationPort=" + destinationPort +
                ", sequenceNumber=" + sequenceNumber +
                ", acknowledgementNumber=" + acknowledgementNumber +
                ", offset=" + offset +
                ", resolve=" + resolve +
                ", urg=" + urg +
                ", ack=" + ack +
                ", psh=" + psh +
                ", rst=" + rst +
                ", syn=" + syn +
                ", fin=" + fin +
                ", window=" + window +
                ", checksum=" + checksum +
                ", urgPointer=" + urgPointer +
                ", optionAndPadding=" + Arrays.toString(optionAndPadding) +
                '}';
    }
}
