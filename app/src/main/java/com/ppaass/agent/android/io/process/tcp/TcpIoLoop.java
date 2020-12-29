package com.ppaass.agent.android.io.process.tcp;

import io.netty.channel.Channel;

import java.io.OutputStream;
import java.net.InetAddress;
import java.util.concurrent.ConcurrentMap;

public class TcpIoLoop {
    private final InetAddress sourceAddress;
    private final InetAddress destinationAddress;
    private final int sourcePort;
    private final int destinationPort;
    private final String key;
    private TcpIoLoopStatus status;
    private int mss;
    private int windowSizeInByte;
    private Channel remoteChannel;
    private final ConcurrentMap<String, TcpIoLoop> container;
    private final OutputStream remoteToDeviceStream;
    private TcpIoLoopFlowTask flowTask;
    private final long baseRemoteSequence;
    private long baseDeviceSequence;
    private boolean initializeStarted;

    public TcpIoLoop(String key, InetAddress sourceAddress, InetAddress destinationAddress,
                     int sourcePort,
                     int destinationPort,
                     ConcurrentMap<String, TcpIoLoop> container, OutputStream remoteToDeviceStream) {
        this.sourceAddress = sourceAddress;
        this.destinationAddress = destinationAddress;
        this.sourcePort = sourcePort;
        this.destinationPort = destinationPort;
        this.key = key;
        this.container = container;
        this.remoteToDeviceStream = remoteToDeviceStream;
        this.status = TcpIoLoopStatus.CLOSED;
        this.mss = -1;
        this.windowSizeInByte = 0;
        this.baseRemoteSequence = Math.abs((int) (Math.random() * 100000) + Math.abs((int) System.currentTimeMillis()));
        this.initializeStarted = false;
    }

    public synchronized void markInitializeStarted() {
        this.initializeStarted = true;
    }

    public synchronized boolean isInitializeStarted() {
        return initializeStarted;
    }

    public long getBaseRemoteSequence() {
        return baseRemoteSequence;
    }

    public synchronized void setBaseDeviceSequence(long baseDeviceSequence) {
        this.baseDeviceSequence = baseDeviceSequence;
    }

    public synchronized long getBaseDeviceSequence() {
        return baseDeviceSequence;
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

    public synchronized void setWindowSizeInByte(int windowSizeInByte) {
        this.windowSizeInByte = windowSizeInByte;
    }

    public synchronized int getWindowSizeInByte() {
        return windowSizeInByte;
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

    public synchronized void reset() {
        this.status = TcpIoLoopStatus.LISTEN;
        this.initializeStarted = false;
        if (this.remoteChannel != null) {
            if (this.remoteChannel.isOpen()) {
                this.remoteChannel.close();
            }
        }
    }

    public synchronized void destroy() {
        this.container.remove(this.getKey());
        this.status = TcpIoLoopStatus.CLOSED;
        this.mss = 0;
        this.windowSizeInByte = 0;
        if (this.remoteChannel != null) {
            if (this.remoteChannel.isOpen()) {
                this.remoteChannel.close();
            }
        }
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
                ", remoteChannel =" + (remoteChannel == null ? "" : remoteChannel.id().asShortText()) +
                '}';
    }
}
