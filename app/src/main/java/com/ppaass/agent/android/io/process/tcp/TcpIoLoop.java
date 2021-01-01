package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import com.ppaass.agent.android.io.protocol.ip.IpPacket;
import io.netty.channel.Channel;

import java.io.OutputStream;
import java.net.InetAddress;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class TcpIoLoop {
    private final static ScheduledExecutorService delayDestroyExecutor = Executors.newScheduledThreadPool(32);
    private final AtomicLong updateTime;
    private final InetAddress sourceAddress;
    private final InetAddress destinationAddress;
    private final int sourcePort;
    private final int destinationPort;
    private final String key;
    private final AtomicReference<TcpIoLoopStatus> status;
    private int mss;
    private int concreteWindowSizeInByte;
    private final AtomicReference<Channel> remoteChannel;
    private final ConcurrentMap<String, TcpIoLoop> container;
    private final OutputStream remoteToDeviceStream;
    private TcpIoLoopFlowTask flowTask;
    private final AtomicBoolean initializeStarted;
    private final AtomicLong accumulateRemoteToDeviceAcknowledgementNumber;
    private final AtomicLong accumulateRemoteToDeviceSequenceNumber;
    private final Queue<IpPacket> deviceInputQueue;


    public TcpIoLoop(String key, long updateTime, InetAddress sourceAddress, InetAddress destinationAddress,
                     int sourcePort,
                     int destinationPort,
                     ConcurrentMap<String, TcpIoLoop> container, OutputStream remoteToDeviceStream) {
        this.updateTime = new AtomicLong(updateTime);
        this.sourceAddress = sourceAddress;
        this.destinationAddress = destinationAddress;
        this.sourcePort = sourcePort;
        this.destinationPort = destinationPort;
        this.key = key;
        this.container = container;
        this.remoteToDeviceStream = remoteToDeviceStream;
        this.status = new AtomicReference<>(TcpIoLoopStatus.CLOSED);
        this.mss = -1;
        this.accumulateRemoteToDeviceSequenceNumber = new AtomicLong(this.generateRandomNumber());
        this.accumulateRemoteToDeviceAcknowledgementNumber = new AtomicLong(0);
        this.initializeStarted = new AtomicBoolean(false);
        this.deviceInputQueue = new ConcurrentLinkedQueue<>();
        this.remoteChannel = new AtomicReference<>(null);
        this.concreteWindowSizeInByte=0;
    }

    public long getUpdateTime() {
        return updateTime.get();
    }

    public void setUpdateTime(long updateTime) {
        this.updateTime.set(updateTime);
    }

    private long generateRandomNumber() {
        return Math.abs((int) (Math.random() * 100000) + Math.abs((int) System.currentTimeMillis()));
    }

    public void markInitializeStarted() {
        this.initializeStarted.set(true);
    }

    public boolean isInitializeStarted() {
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

    public void setRemoteChannel(Channel remoteChannel) {
        this.remoteChannel.set(remoteChannel);
    }

    public Channel getRemoteChannel() {
        return remoteChannel.get();
    }

    public void setStatus(TcpIoLoopStatus status) {
        this.status.set(status);
    }

    public TcpIoLoopStatus getStatus() {
        return status.get();
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

    public long getAccumulateRemoteToDeviceAcknowledgementNumber() {
        return accumulateRemoteToDeviceAcknowledgementNumber.get();
    }

    public void increaseAccumulateRemoteToDeviceAcknowledgementNumber(
            long accumulateRemoteToDeviceAcknowledgementNumber) {
        this.accumulateRemoteToDeviceAcknowledgementNumber.addAndGet(accumulateRemoteToDeviceAcknowledgementNumber);
    }

    public void setAccumulateRemoteToDeviceAcknowledgementNumber(
            long accumulateRemoteToDeviceAcknowledgementNumber) {
        this.accumulateRemoteToDeviceAcknowledgementNumber.set(accumulateRemoteToDeviceAcknowledgementNumber);
    }

    public long getAccumulateRemoteToDeviceSequenceNumber() {
        return accumulateRemoteToDeviceSequenceNumber.get();
    }

    public void increaseAccumulateRemoteToDeviceSequenceNumber(
            long accumulateRemoteToDeviceSequenceNumber) {
        this.accumulateRemoteToDeviceSequenceNumber.addAndGet(accumulateRemoteToDeviceSequenceNumber);
    }

    public void setAccumulateRemoteToDeviceSequenceNumber(
            long accumulateRemoteToDeviceSequenceNumber) {
        this.accumulateRemoteToDeviceSequenceNumber.set(accumulateRemoteToDeviceSequenceNumber);
    }

    public Queue<IpPacket> getDeviceInputQueue() {
        return deviceInputQueue;
    }

    public void setConcreteWindowSizeInByte(int concreteWindowSizeInByte) {
        this.concreteWindowSizeInByte = concreteWindowSizeInByte;
    }

    public int getConcreteWindowSizeInByte() {
        return concreteWindowSizeInByte;
    }

    public void destroy() {
        delayDestroyExecutor.schedule(() -> {
            this.container.remove(this.getKey());
            this.concreteWindowSizeInByte=0;
            this.initializeStarted.set(false);
            this.status.set(TcpIoLoopStatus.CLOSED);
            this.accumulateRemoteToDeviceSequenceNumber.set(this.generateRandomNumber());
            this.accumulateRemoteToDeviceAcknowledgementNumber.set(0);
            this.deviceInputQueue.clear();
            if (this.remoteChannel.get() != null) {
                if (this.remoteChannel.get().isOpen()) {
                    this.remoteChannel.get().close();
                }
            }
            Log.d(TcpIoLoop.class.getName(), "Tcp io loop DESTROYED, tcp loop = " + this);
        }, 10, TimeUnit.SECONDS);
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
                ", concreteWindowSizeInByte=" + concreteWindowSizeInByte +
                ", initializeStarted=" + initializeStarted +
                ", remoteChannel =" + (remoteChannel.get() == null ? "" : remoteChannel.get().id().asShortText()) +
                ", container = (size:" + container.size() + ")" +
                ", deviceInputQueue = (size:" + deviceInputQueue.size() + ")" +
                ", accumulateRemoteToDeviceSequenceNumber = " + this.accumulateRemoteToDeviceSequenceNumber +
                ", accumulateRemoteToDeviceAcknowledgementNumber = " +
                this.accumulateRemoteToDeviceAcknowledgementNumber +
                '}';
    }
}
