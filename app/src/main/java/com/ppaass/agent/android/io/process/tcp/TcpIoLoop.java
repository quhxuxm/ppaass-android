package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import com.ppaass.agent.android.io.protocol.ip.IpPacket;
import io.netty.channel.Channel;

import java.io.OutputStream;
import java.net.InetAddress;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

public class TcpIoLoop {
    public static final int BASE_TCP_LOOP_SEQUENCE = (int) (Math.random() * 100000);
    private static final int QUEUE_TIMEOUT = 20000;
    private final InetAddress sourceAddress;
    private final InetAddress destinationAddress;
    private final int sourcePort;
    private final int destinationPort;
    private final String key;
    private TcpIoLoopStatus status;
    private int mss;
    private int windowSizeInByte;
    private int currentWindowSizeInByte;
    private Channel remoteChannel;
    private final BlockingDeque<IpPacket> deviceToRemoteIpPacketQueue;
    private final ConcurrentMap<String, TcpIoLoop> container;
    private final OutputStream remoteToDeviceStream;
    private TcpIoLoopFlowTask flowTask;

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
        this.currentWindowSizeInByte = 0;
        this.deviceToRemoteIpPacketQueue = new LinkedBlockingDeque<>(1024);
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

    public boolean offerDeviceToRemoteIpPacket(IpPacket ipPacket) {
        try {
            boolean result = this.deviceToRemoteIpPacketQueue.offer(ipPacket, QUEUE_TIMEOUT, TimeUnit.SECONDS);
            this.flowTask.resumeToReadMore();
            return result;
        } catch (InterruptedException e) {
            Log.e(TcpIoLoopFlowTask.class.getName(),
                    "Fail to put ip packet into the device to remote queue because of exception, tcp loop = " +
                            this, e);
            return false;
        }
    }

    public IpPacket pollDeviceToRemoteIpPacket() {
        try {
            return this.deviceToRemoteIpPacketQueue.poll(QUEUE_TIMEOUT, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.e(TcpIoLoopFlowTask.class.getName(),
                    "Fail to take ip packet from the device to remote queue because of exception, tcp loop = " +
                            this, e);
            return null;
        }
    }

    public synchronized int getCurrentWindowSizeInByte() {
        return currentWindowSizeInByte;
    }

    public OutputStream getRemoteToDeviceStream() {
        return remoteToDeviceStream;
    }

    public void setFlowTask(TcpIoLoopFlowTask flowTask) {
        this.flowTask = flowTask;
    }

    public void destroy() {
        synchronized (this) {
            this.container.remove(this.getKey());
            this.status = TcpIoLoopStatus.CLOSED;
            this.deviceToRemoteIpPacketQueue.clear();
            this.currentWindowSizeInByte = 0;
            this.flowTask.stop();
            if (this.getRemoteChannel() != null) {
                if (this.getRemoteChannel().isOpen()) {
                    this.getRemoteChannel().close();
                }
            }
//        this.windowTask.stop();
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
