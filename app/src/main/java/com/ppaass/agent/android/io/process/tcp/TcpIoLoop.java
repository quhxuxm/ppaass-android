package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import com.ppaass.agent.android.io.protocol.ip.IpPacket;
import com.ppaass.agent.android.io.protocol.tcp.TcpPacket;
import io.netty.channel.Channel;

import java.io.OutputStream;
import java.net.InetAddress;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

public class TcpIoLoop {
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
    private final long baseRemoteSequence;
    private long baseDeviceSequence;
    private final BlockingDeque<IpPacket> deviceToRemoteIpPacketQueue;
    private final ConcurrentMap<String, TcpIoLoop> container;
    private final BlockingDeque<IpPacket> window;
    private final OutputStream remoteToDeviceStream;
    private TcpIoLoopFlowTask flowTask;
    private TcpIoLoopWindowTask windowTask;
    private long expectDeviceSequence;
    private long remoteSequence;

    public TcpIoLoop(String key, long baseRemoteSequence, InetAddress sourceAddress, InetAddress destinationAddress,
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
        this.baseRemoteSequence = baseRemoteSequence;
        this.deviceToRemoteIpPacketQueue = new LinkedBlockingDeque<>(1024);
        this.window = new LinkedBlockingDeque<>(1024);
        this.expectDeviceSequence = 0;
        this.remoteSequence = 0;
    }

    public long getBaseRemoteSequence() {
        return baseRemoteSequence;
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

    public void setBaseDeviceSequence(long baseDeviceSequence) {
        this.baseDeviceSequence = baseDeviceSequence;
    }

    public long getBaseDeviceSequence() {
        return baseDeviceSequence;
    }

    public boolean offerDeviceToRemoteIpPacket(IpPacket ipPacket) {
        try {
            return this.deviceToRemoteIpPacketQueue.offer(ipPacket, QUEUE_TIMEOUT, TimeUnit.SECONDS);
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

    public synchronized boolean offerIpPacketToWindow(IpPacket ipPacket) {
        try {
            TcpPacket tcpPacketInWindow = (TcpPacket) ipPacket.getData();
            int tcpPacketDataLength = tcpPacketInWindow.getData().length;
            this.currentWindowSizeInByte += tcpPacketDataLength;
            return this.window.offer(ipPacket, QUEUE_TIMEOUT, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.e(TcpIoLoopFlowTask.class.getName(),
                    "Fail to put ip packet into the window queue because of exception, tcp loop = " +
                            this, e);
            return false;
        }
    }

    public synchronized IpPacket pollIpPacketFromWindow() {
        IpPacket result = this.window.poll();
        if (result == null) {
            return null;
        }
        TcpPacket tcpPacketInWindow = (TcpPacket) result.getData();
        int tcpPacketDataLength = tcpPacketInWindow.getData().length;
        this.currentWindowSizeInByte = this.currentWindowSizeInByte - tcpPacketDataLength;
        return result;
    }

    public IpPacket peekIpPacketFromWindow() {
        return this.window.peek();
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

    public void setWindowTask(TcpIoLoopWindowTask remoteToDeviceTask) {
        this.windowTask = remoteToDeviceTask;
    }

    public TcpIoLoopWindowTask getWindowTask() {
        return windowTask;
    }

    public long getExpectDeviceSequence() {
        return expectDeviceSequence;
    }

    public void setExpectDeviceSequence(long expectDeviceSequence) {
        this.expectDeviceSequence = expectDeviceSequence;
    }

    public long getRemoteSequence() {
        return remoteSequence;
    }

    public void setRemoteSequence(long remoteSequence) {
        this.remoteSequence = remoteSequence;
    }

    public void destroy() {
        this.setStatus(TcpIoLoopStatus.CLOSED);
        this.remoteSequence = 0;
        this.expectDeviceSequence = 0;
        if (this.getRemoteChannel() != null) {
            if (this.getRemoteChannel().isOpen()) {
                this.getRemoteChannel().close();
            }
        }
        this.deviceToRemoteIpPacketQueue.clear();
        this.container.remove(this.getKey());
        this.window.clear();
        this.currentWindowSizeInByte = 0;
        this.flowTask.stop();
        this.windowTask.stop();
    }

    @Override
    public String toString() {
        return "TcpIoLoop{" +
                "key='" + key + '\'' +
                ", baseDeviceSequence=" + baseDeviceSequence +
                ", baseRemoteSequence=" + baseRemoteSequence +
                ", sourceAddress=" + sourceAddress +
                ", destinationAddress=" + destinationAddress +
                ", sourcePort=" + sourcePort +
                ", destinationPort=" + destinationPort +
                ", status=" + status +
                ", mss=" + mss +
                ", remoteSequence[sequenceNumber]=" + remoteSequence +
                ", expectDeviceSequence[acknowledgementNumber]=" + expectDeviceSequence +
                ", relativeRemoteSequence[relative sequenceNumber]=" + (remoteSequence - baseRemoteSequence) +
                ", relativeExpectDeviceSequence[relative acknowledgementNumber]=" +
                (expectDeviceSequence - baseDeviceSequence) +
                ", currentWindowSizeInByte=" + currentWindowSizeInByte +
                ", window=[size:" + window.size() + "]" +
                ", remoteChannel =" + (remoteChannel == null ? "" : remoteChannel.id().asShortText()) +
                '}';
    }
}
