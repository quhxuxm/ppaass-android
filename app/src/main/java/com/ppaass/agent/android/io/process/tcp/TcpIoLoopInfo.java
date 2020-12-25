package com.ppaass.agent.android.io.process.tcp;

import io.netty.channel.Channel;

import java.net.InetAddress;
import java.util.concurrent.Semaphore;

public class TcpIoLoopInfo {
    private final InetAddress sourceAddress;
    private final InetAddress destinationAddress;
    private final int sourcePort;
    private final int destinationPort;
    private final String key;
    private TcpIoLoopStatus status;
    private long currentRemoteToDeviceSeq;
    private long currentRemoteToDeviceAck;
    private int mss;
    private int window;
    private Channel remoteChannel;
    private final Semaphore ackSemaphore;
    private final int baseLoopSequence;
    private long latestMessageTime;

    public TcpIoLoopInfo(String key, int baseLoopSequence, InetAddress sourceAddress, InetAddress destinationAddress, int sourcePort,
                         int destinationPort) {
        this.sourceAddress = sourceAddress;
        this.destinationAddress = destinationAddress;
        this.sourcePort = sourcePort;
        this.destinationPort = destinationPort;
        this.key = key;
        this.status = TcpIoLoopStatus.CLOSED;
        this.mss = -1;
        this.window = -1;
        this.ackSemaphore = new Semaphore(1);
        this.baseLoopSequence=baseLoopSequence;
    }

    public int getBaseLoopSequence() {
        return baseLoopSequence;
    }

    public String getKey() {
        return key;
    }

    public int getMss() {
        return mss;
    }

    public void setMss(int mss) {
        this.mss = mss;
    }

    public InetAddress getSourceAddress() {
        return sourceAddress;
    }

    public InetAddress getDestinationAddress() {
        return destinationAddress;
    }

    public int getSourcePort() {
        return sourcePort;
    }

    public int getDestinationPort() {
        return destinationPort;
    }

    public synchronized void setRemoteChannel(Channel remoteChannel) {
        this.remoteChannel = remoteChannel;
    }

    public synchronized Channel getRemoteChannel() {
        return remoteChannel;
    }

    public synchronized void setWindow(int window) {
        this.window = window;
    }

    public synchronized int getWindow() {
        return window;
    }

    public synchronized void setStatus(TcpIoLoopStatus status) {
        this.status = status;
    }

    public synchronized TcpIoLoopStatus getStatus() {
        return status;
    }

    public synchronized long getCurrentRemoteToDeviceSeq() {
        return currentRemoteToDeviceSeq;
    }

    public synchronized void setCurrentRemoteToDeviceSeq(long currentRemoteToDeviceSeq) {
        this.currentRemoteToDeviceSeq = currentRemoteToDeviceSeq;
    }

    public synchronized long getCurrentRemoteToDeviceAck() {
        return currentRemoteToDeviceAck;
    }

    public synchronized void setCurrentRemoteToDeviceAck(long currentRemoteToDeviceAck) {
        this.currentRemoteToDeviceAck = currentRemoteToDeviceAck;
    }

    public Semaphore getAckSemaphore() {
        return ackSemaphore;
    }

    public void setLatestMessageTime(long latestMessageTime) {
        this.latestMessageTime = latestMessageTime;
    }

    public long getLatestMessageTime() {
        return latestMessageTime;
    }

    @Override
    public String toString() {
        return "TcpIoLoop{" +
                "key='" + key + '\'' +
                ", baseLoopSequence="+baseLoopSequence+
                ", sourceAddress=" + sourceAddress +
                ", destinationAddress=" + destinationAddress +
                ", sourcePort=" + sourcePort +
                ", destinationPort=" + destinationPort +
                ", status=" + status +
                ", mss=" + mss +
                ", latestMessageTime= "+latestMessageTime+
                ", currentRemoteToDeviceSeq=" + currentRemoteToDeviceSeq +
                ", currentRemoteToDeviceAck=" + currentRemoteToDeviceAck +
                ", remoteChannel =" + (remoteChannel == null ? "" : remoteChannel.id().asShortText()) +
                '}';
    }
}
