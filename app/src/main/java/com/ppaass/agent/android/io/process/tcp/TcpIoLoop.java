package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import com.ppaass.agent.android.io.protocol.ip.IpPacket;
import io.netty.channel.Channel;

import java.io.OutputStream;
import java.net.InetAddress;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class TcpIoLoop {
    private long updateTime;
    private final InetAddress sourceAddress;
    private final InetAddress destinationAddress;
    private final int sourcePort;
    private final int destinationPort;
    private final String key;
    private TcpIoLoopStatus status;
    private int mss;
    private Channel remoteChannel;
    private final ConcurrentMap<String, TcpIoLoop> container;
    private final OutputStream remoteToDeviceStream;
    private TcpIoLoopFlowTask flowTask;
    private AtomicBoolean initializeStarted;
    private AtomicLong accumulateRemoteToDeviceAcknowledgementNumber;
    private AtomicLong accumulateRemoteToDeviceSequenceNumber;
    private final Queue<IpPacket> deviceInputQueue;

    public TcpIoLoop(String key, long updateTime, InetAddress sourceAddress, InetAddress destinationAddress,
                     int sourcePort,
                     int destinationPort,
                     ConcurrentMap<String, TcpIoLoop> container, OutputStream remoteToDeviceStream) {
        this.updateTime = updateTime;
        this.sourceAddress = sourceAddress;
        this.destinationAddress = destinationAddress;
        this.sourcePort = sourcePort;
        this.destinationPort = destinationPort;
        this.key = key;
        this.container = container;
        this.remoteToDeviceStream = remoteToDeviceStream;
        this.status = TcpIoLoopStatus.CLOSED;
        this.mss = -1;
        this.accumulateRemoteToDeviceSequenceNumber = new AtomicLong(this.generateRandomNumber());
        this.accumulateRemoteToDeviceAcknowledgementNumber = new AtomicLong(0);
        this.initializeStarted = new AtomicBoolean(false);
        this.deviceInputQueue = new ConcurrentLinkedQueue<>();
    }

    public synchronized long getUpdateTime() {
        return updateTime;
    }

    public synchronized void setUpdateTime(long updateTime) {
        this.updateTime = updateTime;
    }

    private long generateRandomNumber() {
        return Math.abs((int) (Math.random() * 100000) + Math.abs((int) System.currentTimeMillis()));
    }

    public synchronized void markInitializeStarted() {
        this.initializeStarted.set(true);
    }

    public synchronized boolean isInitializeStarted() {
        return initializeStarted.get();
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

    public synchronized void setStatus(TcpIoLoopStatus status) {
        this.status = status;
    }

    public synchronized TcpIoLoopStatus getStatus() {
        return status;
    }

    public TcpIoLoopFlowTask getFlowTask() {
        return flowTask;
    }

    public OutputStream getRemoteToDeviceStream() {
        return remoteToDeviceStream;
    }

    public void setFlowTask(TcpIoLoopFlowTask flowTask) {
        this.flowTask = flowTask;
    }

    public synchronized long getAccumulateRemoteToDeviceAcknowledgementNumber() {
        return accumulateRemoteToDeviceAcknowledgementNumber.get();
    }

    public synchronized void increaseAccumulateRemoteToDeviceAcknowledgementNumber(
            long accumulateRemoteToDeviceAcknowledgementNumber) {
        this.accumulateRemoteToDeviceAcknowledgementNumber.addAndGet(accumulateRemoteToDeviceAcknowledgementNumber);
    }

    public synchronized long getAccumulateRemoteToDeviceSequenceNumber() {
        return accumulateRemoteToDeviceSequenceNumber.get();
    }

    public synchronized void increaseAccumulateRemoteToDeviceSequenceNumber(
            long accumulateRemoteToDeviceSequenceNumber) {
        this.accumulateRemoteToDeviceSequenceNumber.addAndGet(accumulateRemoteToDeviceSequenceNumber);
    }

    public Queue<IpPacket> getDeviceInputQueue() {
        return deviceInputQueue;
    }

    public synchronized void reset() {
        this.status = TcpIoLoopStatus.LISTEN;
        this.initializeStarted.set(false);
        this.accumulateRemoteToDeviceSequenceNumber = new AtomicLong(this.generateRandomNumber());
        this.accumulateRemoteToDeviceAcknowledgementNumber = new AtomicLong(0);
        this.deviceInputQueue.clear();
        Log.d(TcpIoLoop.class.getName(), "Tcp io loop RESET, tcp loop = " + this);
    }

    public synchronized void destroy() {
        this.container.remove(this.getKey());
        this.initializeStarted.set(false);
        this.status = TcpIoLoopStatus.CLOSED;
        this.accumulateRemoteToDeviceSequenceNumber = new AtomicLong(this.generateRandomNumber());
        this.accumulateRemoteToDeviceAcknowledgementNumber = new AtomicLong(0);
        this.deviceInputQueue.clear();
        Log.d(TcpIoLoop.class.getName(), "Tcp io loop DESTROYED, tcp loop = " + this);
    }

    @Override
    public String toString() {
        return "TcpIoLoop{" +
                "key='" + key + '\'' +
                ", sourceAddress=" + sourceAddress +
                ", destinationAddress=" + destinationAddress +
                ", sourcePort=" + sourcePort +
                ", destinationPort=" + destinationPort +
                ", status=" + status +
                ", mss=" + mss +
                ", initializeStarted=" + initializeStarted +
                ", remoteChannel =" + (remoteChannel == null ? "" : remoteChannel.id().asShortText()) +
                ", container = (size:" + container.size() + ")" +
                ", deviceInputQueue = (size:" + deviceInputQueue.size() + ")" +
                ", accumulateRemoteToDeviceSequenceNumber = " + this.accumulateRemoteToDeviceSequenceNumber +
                ", accumulateRemoteToDeviceAcknowledgementNumber = " +
                this.accumulateRemoteToDeviceAcknowledgementNumber +
                '}';
    }
}
