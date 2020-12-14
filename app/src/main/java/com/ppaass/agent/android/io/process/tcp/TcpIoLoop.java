package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import com.ppaass.agent.android.io.process.IIoLoop;
import com.ppaass.agent.android.io.protocol.ip.IpPacket;
import io.netty.bootstrap.Bootstrap;

import java.io.FileOutputStream;
import java.net.InetAddress;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;

public class TcpIoLoop implements IIoLoop<TcpIoLoopVpntoAppData> {
    private static final ExecutorService inputWorkerExecutorService = Executors.newFixedThreadPool(10);
    private static final ExecutorService outputWorkerExecutorService = Executors.newFixedThreadPool(10);
    private final InetAddress sourceAddress;
    private final InetAddress destinationAddress;
    private final int sourcePort;
    private final int destinationPort;
    private final String key;
    private TcpIoLoopStatus status;
    private final Bootstrap proxyTcpBootstrap;
    private TcpIoLoopAppToVpnWorker appToVpnWorker;
    private TcpIoLoopVpnToAppWorker vpnToAppWorker;
    private final BlockingDeque<IpPacket> inputIpPacketQueue;
    private final BlockingDeque<TcpIoLoopVpntoAppData> outputDataQueue;
    private final FileOutputStream vpnOutputStream;
    private long appToVpnSequenceNumber;
    private long appToVpnAcknowledgementNumber;
    private long vpnToAppSequenceNumber;
    private long vpnToAppAcknowledgementNumber;

    public TcpIoLoop(InetAddress sourceAddress, InetAddress destinationAddress, int sourcePort, int destinationPort,
                     String key, Bootstrap proxyTcpBootstrap, FileOutputStream vpnOutputStream) {
        this.sourceAddress = sourceAddress;
        this.destinationAddress = destinationAddress;
        this.sourcePort = sourcePort;
        this.destinationPort = destinationPort;
        this.key = key;
        this.proxyTcpBootstrap = proxyTcpBootstrap;
        this.vpnOutputStream = vpnOutputStream;
        this.status = TcpIoLoopStatus.LISTEN;
        this.inputIpPacketQueue = new LinkedBlockingDeque<>();
        this.outputDataQueue = new LinkedBlockingDeque<>();
    }

    @Override
    public String getKey() {
        return this.key;
    }

    @Override
    public void init() {
        this.appToVpnWorker = new TcpIoLoopAppToVpnWorker(this, this.proxyTcpBootstrap, this.inputIpPacketQueue,
                this.outputDataQueue);
        this.vpnToAppWorker = new TcpIoLoopVpnToAppWorker(this, this.outputDataQueue, vpnOutputStream);
    }

    @Override
    public final void start() {
        this.appToVpnWorker.start();
        this.vpnToAppWorker.start();
        inputWorkerExecutorService.submit(this.appToVpnWorker);
        outputWorkerExecutorService.submit(this.vpnToAppWorker);
    }

    @Override
    public void offerInputIpPacket(IpPacket ipPacket) {
        Log.d(TcpIoLoop.class.getName(),
                "Offer ip packet to TcpIoLoopAppToVpnWorker, ip packet = " + ipPacket + ", tcp loop = " + this);
        this.appToVpnWorker.offerIpPacket(ipPacket);
    }

    @Override
    public void offerOutputData(TcpIoLoopVpntoAppData outputData) {
        Log.d(TcpIoLoop.class.getName(),
                "Offer output data to TcpIoLoopVpnToAppWorker, output data = " + outputData + ", tcp loop = " + this);
        this.vpnToAppWorker.offerOutputData(outputData);
    }

    @Override
    public void stop() {
        this.appToVpnWorker.stop();
        this.vpnToAppWorker.stop();
    }

    public synchronized void switchStatus(TcpIoLoopStatus inputStatus) {
        if (inputStatus == null) {
            return;
        }
        this.status = inputStatus;
    }

    public synchronized long getAppToVpnSequenceNumber() {
        return appToVpnSequenceNumber;
    }

    public synchronized void setAppToVpnSequenceNumber(long appToVpnSequenceNumber) {
        this.appToVpnSequenceNumber = appToVpnSequenceNumber;
    }

    public synchronized long getAppToVpnAcknowledgementNumber() {
        return appToVpnAcknowledgementNumber;
    }

    public synchronized void setAppToVpnAcknowledgementNumber(long appToVpnAcknowledgementNumber) {
        this.appToVpnAcknowledgementNumber = appToVpnAcknowledgementNumber;
    }

    public synchronized long getVpnToAppSequenceNumber() {
        return vpnToAppSequenceNumber;
    }

    public synchronized void setVpnToAppSequenceNumber(long vpnToAppSequenceNumber) {
        this.vpnToAppSequenceNumber = vpnToAppSequenceNumber;
    }

    public synchronized long getVpnToAppAcknowledgementNumber() {
        return vpnToAppAcknowledgementNumber;
    }

    public synchronized void setVpnToAppAcknowledgementNumber(long vpnToAppAcknowledgementNumber) {
        this.vpnToAppAcknowledgementNumber = vpnToAppAcknowledgementNumber;
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

    public TcpIoLoopStatus getStatus() {
        return status;
    }

    @Override
    public String toString() {
        return "TcpIoLoop{" +
                "sourceAddress=" + sourceAddress +
                ", destinationAddress=" + destinationAddress +
                ", sourcePort=" + sourcePort +
                ", destinationPort=" + destinationPort +
                ", key='" + key + '\'' +
                ", status=" + status +
                ", inputIpPacketQueue=" + inputIpPacketQueue +
                ", outputDataQueue=" + outputDataQueue +
                ", appToVpnSequenceNumber=" + appToVpnSequenceNumber +
                ", appToVpnAcknowledgementNumber=" + appToVpnAcknowledgementNumber +
                ", vpnToAppSequenceNumber=" + vpnToAppSequenceNumber +
                ", vpnToAppAcknowledgementNumber=" + vpnToAppAcknowledgementNumber +
                '}';
    }
}
