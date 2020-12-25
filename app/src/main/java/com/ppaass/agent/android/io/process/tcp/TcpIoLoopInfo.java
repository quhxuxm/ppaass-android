package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import com.ppaass.agent.android.io.protocol.ip.IpPacket;
import io.netty.channel.Channel;

import java.net.InetAddress;
import java.util.concurrent.*;

public class TcpIoLoopInfo {
    private static final int QUEUE_TIMEOUT = 20000;
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
    private final Semaphore exchangeSemaphore;
    private final int baseLoopSequence;
    private long latestMessageTime;
    private final BlockingDeque<IpPacket> deviceToRemoteIpPacketQueue;
    private final BlockingDeque<IpPacket> remoteToDeviceIpPacketQueue;
    private final ConcurrentMap<String, TcpIoLoopInfo> container;
    private TcpIoLoopRemoteToDeviceTask remoteToDeviceTask;
    private TcpIoLoopDeviceToRemoteTask deviceToRemoteTask;

    public TcpIoLoopInfo(String key, int baseLoopSequence, InetAddress sourceAddress, InetAddress destinationAddress,
                         int sourcePort,
                         int destinationPort,
                         ConcurrentMap<String, TcpIoLoopInfo> container) {
        this.sourceAddress = sourceAddress;
        this.destinationAddress = destinationAddress;
        this.sourcePort = sourcePort;
        this.destinationPort = destinationPort;
        this.key = key;
        this.container = container;
        this.status = TcpIoLoopStatus.CLOSED;
        this.mss = -1;
        this.window = -1;
        this.exchangeSemaphore = new Semaphore(1);
        this.baseLoopSequence = baseLoopSequence;
        this.deviceToRemoteIpPacketQueue = new LinkedBlockingDeque<>(1024);
        this.remoteToDeviceIpPacketQueue = new LinkedBlockingDeque<>(1024);
    }

    public void setDeviceToRemoteTask(TcpIoLoopDeviceToRemoteTask deviceToRemoteTask) {
        this.deviceToRemoteTask = deviceToRemoteTask;
    }

    public void setRemoteToDeviceTask(TcpIoLoopRemoteToDeviceTask remoteToDeviceTask) {
        this.remoteToDeviceTask = remoteToDeviceTask;
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

    public Semaphore getExchangeSemaphore() {
        return exchangeSemaphore;
    }

    public void setLatestMessageTime(long latestMessageTime) {
        this.latestMessageTime = latestMessageTime;
    }

    public long getLatestMessageTime() {
        return latestMessageTime;
    }

    public boolean offerDeviceToRemoteIpPacket(IpPacket ipPacket) {
        try {
            return this.deviceToRemoteIpPacketQueue.offer(ipPacket, QUEUE_TIMEOUT, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.e(TcpIoLoopDeviceToRemoteTask.class.getName(),
                    "Fail to put ip packet into the device to remote queue because of exception, tcp loop = " +
                            this, e);
            return false;
        }
    }

    public IpPacket pollDeviceToRemoteIpPacket() {
        try {
            return this.deviceToRemoteIpPacketQueue.poll(QUEUE_TIMEOUT, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.e(TcpIoLoopDeviceToRemoteTask.class.getName(),
                    "Fail to take ip packet from the device to remote queue because of exception, tcp loop = " +
                            this, e);
            return null;
        }
    }

    public boolean offerRemoteToDeviceIpPacket(IpPacket ipPacket) {
        try {
            return this.remoteToDeviceIpPacketQueue.offer(ipPacket, QUEUE_TIMEOUT, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.e(TcpIoLoopDeviceToRemoteTask.class.getName(),
                    "Fail to put ip packet into the remote to device queue because of exception, tcp loop = " +
                            this, e);
            return false;
        }
    }

    public IpPacket pollRemoteToDeviceIpPacket() {
        try {
            return this.remoteToDeviceIpPacketQueue.poll(QUEUE_TIMEOUT, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.e(TcpIoLoopDeviceToRemoteTask.class.getName(),
                    "Fail to take ip packet from the remote to device queue because of exception, tcp loop = " +
                            this, e);
            return null;
        }
    }

    public void destroy() {
        this.setStatus(TcpIoLoopStatus.CLOSED);
        this.setCurrentRemoteToDeviceAck(-1);
        this.setCurrentRemoteToDeviceSeq(-1);
        this.getExchangeSemaphore().release();
        if (this.getRemoteChannel() != null) {
            if (this.getRemoteChannel().isOpen()) {
                this.getRemoteChannel().close();
            }
        }
        this.deviceToRemoteIpPacketQueue.clear();
        this.remoteToDeviceIpPacketQueue.clear();
        this.container.remove(this.getKey());
        this.deviceToRemoteTask.stop();
        this.remoteToDeviceTask.stop();
    }

    @Override
    public String toString() {
        return "TcpIoLoop{" +
                "key='" + key + '\'' +
                ", baseLoopSequence=" + baseLoopSequence +
                ", sourceAddress=" + sourceAddress +
                ", destinationAddress=" + destinationAddress +
                ", sourcePort=" + sourcePort +
                ", destinationPort=" + destinationPort +
                ", status=" + status +
                ", mss=" + mss +
                ", latestMessageTime= " + latestMessageTime +
                ", currentRemoteToDeviceSeq=" + currentRemoteToDeviceSeq +
                ", currentRemoteToDeviceAck=" + currentRemoteToDeviceAck +
                ", remoteChannel =" + (remoteChannel == null ? "" : remoteChannel.id().asShortText()) +
                '}';
    }
}
