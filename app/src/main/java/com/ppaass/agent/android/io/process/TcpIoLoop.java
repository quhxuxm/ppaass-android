package com.ppaass.agent.android.io.process;

import io.netty.channel.Channel;
import org.apache.commons.pool2.impl.GenericObjectPool;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class TcpIoLoop {
    private final AtomicLong updateTime;
    private final InetAddress sourceAddress;
    private final InetAddress destinationAddress;
    private final int sourcePort;
    private final int destinationPort;
    private final String key;
    private final AtomicReference<TcpIoLoopStatus> status;
    private int mss;
//    private int concreteWindowSizeInByte;
    private final AtomicReference<Channel> proxyTcpChannel;
    private final ConcurrentMap<String, TcpIoLoop> container;
    private final AtomicLong accumulateRemoteToDeviceAcknowledgementNumber;
    private final AtomicLong accumulateRemoteToDeviceSequenceNumber;
//    private final GenericObjectPool<Channel> proxyTcpChannelPool;

    public TcpIoLoop(String key, long updateTime, byte[] sourceAddressInBytes, byte[] destinationAddressInBytes,
                     int sourcePort,
                     int destinationPort,
                     ConcurrentMap<String, TcpIoLoop> container
//                     GenericObjectPool<Channel> proxyTcpChannelPool
    ) {
        this.updateTime = new AtomicLong(updateTime);
//        this.proxyTcpChannelPool = proxyTcpChannelPool;
        try {
            this.sourceAddress = InetAddress.getByAddress(sourceAddressInBytes);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
        try {
            this.destinationAddress = InetAddress.getByAddress(destinationAddressInBytes);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
        this.sourcePort = sourcePort;
        this.destinationPort = destinationPort;
        this.key = key;
        this.container = container;
        this.status = new AtomicReference<>(TcpIoLoopStatus.CLOSED);
        this.mss = -1;
        this.accumulateRemoteToDeviceSequenceNumber = new AtomicLong(this.generateRandomNumber());
        this.accumulateRemoteToDeviceAcknowledgementNumber = new AtomicLong(0);
        this.proxyTcpChannel = new AtomicReference<>(null);
//        this.concreteWindowSizeInByte = 0;
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

    public void setProxyTcpChannel(Channel proxyTcpChannel) {
        this.proxyTcpChannel.set(proxyTcpChannel);
    }

    public Channel getProxyTcpChannel() {
        return proxyTcpChannel.get();
    }

    public void setStatus(TcpIoLoopStatus status) {
        this.status.set(status);
    }

    public TcpIoLoopStatus getStatus() {
        return status.get();
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

    public long increaseAccumulateRemoteToDeviceSequenceNumber(
            long accumulateRemoteToDeviceSequenceNumber) {
        return this.accumulateRemoteToDeviceSequenceNumber.addAndGet(accumulateRemoteToDeviceSequenceNumber);
    }

    public void setAccumulateRemoteToDeviceSequenceNumber(
            long accumulateRemoteToDeviceSequenceNumber) {
        this.accumulateRemoteToDeviceSequenceNumber.set(accumulateRemoteToDeviceSequenceNumber);
    }

//    public void setConcreteWindowSizeInByte(int concreteWindowSizeInByte) {
//        this.concreteWindowSizeInByte = concreteWindowSizeInByte;
//    }
//
//    public int getConcreteWindowSizeInByte() {
//        return concreteWindowSizeInByte;
//    }

    public void destroy() {
        synchronized (this.container) {
            this.container.remove(this.getKey());
        }
//        this.concreteWindowSizeInByte = 0;
        this.status.set(TcpIoLoopStatus.CLOSED);
        this.accumulateRemoteToDeviceSequenceNumber.set(this.generateRandomNumber());
        this.accumulateRemoteToDeviceAcknowledgementNumber.set(0);
//        this.proxyTcpChannelPool.returnObject(this.proxyTcpChannel.get());
//        if (this.remoteChannel.get() != null) {
//            if (this.remoteChannel.get().isOpen()) {
//                this.remoteChannel.get().close();
//            }
//        }
//        Log.d(TcpIoLoop.class.getName(), "Tcp io loop DESTROYED, tcp loop = " + this);
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
//                ", mss=" + mss +
//                ", concreteWindowSizeInByte=" + concreteWindowSizeInByte +
                ", remoteChannel =" + (proxyTcpChannel.get() == null ? "" : proxyTcpChannel.get().id().asShortText()) +
                ", container = (size:" + container.size() + ")" +
                ", accumulateRemoteToDeviceSequenceNumber = " + this.accumulateRemoteToDeviceSequenceNumber +
                ", accumulateRemoteToDeviceAcknowledgementNumber = " +
                this.accumulateRemoteToDeviceAcknowledgementNumber +
                '}';
    }
}
