package com.ppaass.agent.android.io.process.tcp;

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
    private TcpIoLoopAppToVpnWorker inputWorker;
    private TcpIoLoopVpnToAppWorker outputWorker;
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
        this.inputWorker = new TcpIoLoopAppToVpnWorker(this, this.proxyTcpBootstrap, this.inputIpPacketQueue,
                this.outputDataQueue);
        this.outputWorker = new TcpIoLoopVpnToAppWorker(this, this.outputDataQueue, vpnOutputStream);
    }

    @Override
    public final void start() {
        this.inputWorker.start();
        this.outputWorker.start();
        inputWorkerExecutorService.submit(this.inputWorker);
        outputWorkerExecutorService.submit(this.outputWorker);
    }

    @Override
    public void offerInputIpPacket(IpPacket ipPacket) {
        this.inputWorker.offerIpPacket(ipPacket);
    }

    @Override
    public void offerOutputData(TcpIoLoopVpntoAppData outputData) {
        this.outputWorker.offerOutputData(outputData);
    }

    @Override
    public void stop() {
        this.inputWorker.stop();
        this.outputWorker.stop();
    }

    public synchronized void switchStatus(TcpIoLoopStatus inputStatus) {
        if (inputStatus == null) {
            return;
        }
        this.status = inputStatus;
    }

    public long getAppToVpnSequenceNumber() {
        return appToVpnSequenceNumber;
    }

    public void setAppToVpnSequenceNumber(long appToVpnSequenceNumber) {
        this.appToVpnSequenceNumber = appToVpnSequenceNumber;
    }

    public long getAppToVpnAcknowledgementNumber() {
        return appToVpnAcknowledgementNumber;
    }

    public void setAppToVpnAcknowledgementNumber(long appToVpnAcknowledgementNumber) {
        this.appToVpnAcknowledgementNumber = appToVpnAcknowledgementNumber;
    }

    public long getVpnToAppSequenceNumber() {
        return vpnToAppSequenceNumber;
    }

    public void setVpnToAppSequenceNumber(long vpnToAppSequenceNumber) {
        this.vpnToAppSequenceNumber = vpnToAppSequenceNumber;
    }

    public long getVpnToAppAcknowledgementNumber() {
        return vpnToAppAcknowledgementNumber;
    }

    public void setVpnToAppAcknowledgementNumber(long vpnToAppAcknowledgementNumber) {
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
}
