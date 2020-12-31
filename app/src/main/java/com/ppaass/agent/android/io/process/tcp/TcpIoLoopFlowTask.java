package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import com.ppaass.agent.android.io.protocol.ip.IpPacket;
import com.ppaass.agent.android.io.protocol.tcp.TcpHeader;
import com.ppaass.agent.android.io.protocol.tcp.TcpHeaderOption;
import com.ppaass.agent.android.io.protocol.tcp.TcpPacket;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static com.ppaass.agent.android.io.process.tcp.ITcpIoLoopConstant.TCP_LOOP;

class TcpIoLoopFlowTask implements Runnable {
    private static final int DEFAULT_2MSL_TIME = 10;
    private static final int DEFAULT_DELAY_TIME = 10;
    private final TcpIoLoop loop;
    private final Bootstrap remoteBootstrap;
    private final ScheduledExecutorService twoMslTimerExecutor;

    private boolean alive;

    public TcpIoLoopFlowTask(TcpIoLoop loop, Bootstrap remoteBootstrap,
                             ScheduledExecutorService twoMslTimerExecutor) {
        this.loop = loop;
        this.remoteBootstrap = remoteBootstrap;
        this.twoMslTimerExecutor = twoMslTimerExecutor;

        this.alive = false;
    }

    public synchronized void start() {
        this.alive = true;
    }

    public synchronized void stop() {
        this.alive = false;
    }

    @Override
    public void run() {
        while (this.alive) {
            IpPacket inputIpPacket = this.loop.getDeviceInputQueue().poll();
            if (inputIpPacket == null) {
                continue;
            }
            this.execute(inputIpPacket);
        }
    }

    private void execute(IpPacket inputIpPacket) {
        TcpPacket inputTcpPacket = (TcpPacket) inputIpPacket.getData();
        TcpHeader inputTcpHeader = inputTcpPacket.getHeader();
        if (this.loop.getStatus() == TcpIoLoopStatus.CLOSED) {
            Log.e(TcpIoLoopFlowTask.class.getName(),
                    "The tcp loop is CLOSED already, ignore the incoming ip packet, tcp loop = " + this.loop);
            return;
        }
        if (this.loop.getStatus() == TcpIoLoopStatus.TIME_WAITE) {
            Log.e(TcpIoLoopFlowTask.class.getName(),
                    "The tcp loop is TIME_WAITE already, ignore the incoming ip packet, tcp loop = " + this.loop);
            return;
        }
        if (this.loop.getStatus() == TcpIoLoopStatus.CLOSE_WAIT) {
            Log.e(TcpIoLoopFlowTask.class.getName(),
                    "The tcp loop is CLOSE_WAIT already, ignore the incoming ip packet, tcp loop = " + this.loop);
            return;
        }
        if (inputTcpHeader.isSyn()) {
            doSyn(inputTcpHeader);
            return;
        }


            if (inputTcpHeader.isPsh() && inputTcpHeader.isAck()) {
                byte[] inputData = inputTcpPacket.getData();
                if (inputData != null && inputData.length == 0) {
                    inputData = null;
                }
                doPsh(inputTcpHeader, inputData);
                return;
            }
            if ((inputTcpHeader.isFin())) {
                doFin(inputTcpHeader);
                return;
            }
            if (inputTcpHeader.isAck()) {
                byte[] inputData = inputTcpPacket.getData();
                if (inputData != null && inputData.length == 0) {
                    inputData = null;
                }
                doAck(inputTcpHeader, inputData);
                return;
            }
            if (inputTcpHeader.isRst()) {
                doRst(inputTcpHeader);
            }

    }

    private void doSyn(TcpHeader inputTcpHeader) {
        this.loop.increaseAccumulateRemoteToDeviceAcknowledgementNumber(inputTcpHeader.getSequenceNumber());
        if (this.loop.isInitializeStarted()) {
            Log.d(TcpIoLoopFlowTask.class.getName(),
                    "RECEIVE [SYN ACK], duplicate SYN, tcp header = " +
                            inputTcpHeader +
                            ", tcp loop = " + this.loop);

            if (this.loop.getStatus() == TcpIoLoopStatus.SYN_RECEIVED) {
                this.loop.increaseAccumulateRemoteToDeviceAcknowledgementNumber(1);
                IpPacket resetPacket = TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildRstAck(
                        this.loop.getDestinationAddress(),
                        this.loop.getDestinationPort(),
                        this.loop.getSourceAddress(),
                        this.loop.getSourcePort(),
                        this.loop.getAccumulateRemoteToDeviceSequenceNumber(),
                        this.loop.getAccumulateRemoteToDeviceAcknowledgementNumber()
                );
                TcpIoLoopRemoteToDeviceWriter.INSTANCE.writeIpPacketToDevice(resetPacket, this.loop.getKey(),
                        this.loop.getRemoteToDeviceStream());

                return;
            }
            this.loop.increaseAccumulateRemoteToDeviceAcknowledgementNumber(1);
            TcpHeaderOption mssOption = null;
            for (TcpHeaderOption option : inputTcpHeader.getOptions()) {
                if (option.getKind() == TcpHeaderOption.Kind.MSS) {
                    mssOption = option;
                    break;
                }
            }
            if (mssOption != null) {
                ByteBuf mssOptionBuf = Unpooled.wrappedBuffer(mssOption.getInfo());
                int mss = mssOptionBuf.readUnsignedShort();
                this.loop.setMss(mss);
            }
            Log.d(TcpIoLoopFlowTask.class.getName(),
                    "RECEIVE [SYN], initializing connection SUCCESS, switch tcp loop to SYN_RECIVED, tcp header = " +
                            inputTcpHeader +
                            ", tcp loop = " + this.loop);
//            this.loop.increaseAccumulateRemoteToDeviceSequenceNumber(1);
            IpPacket synAckPacket = TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildSynAck(
                    this.loop.getDestinationAddress(),
                    this.loop.getDestinationPort(),
                    this.loop.getSourceAddress(),
                    this.loop.getSourcePort(),
                    this.loop.getAccumulateRemoteToDeviceSequenceNumber(),
                    this.loop.getAccumulateRemoteToDeviceAcknowledgementNumber(),
                    this.loop.getMss()
            );
            TcpIoLoopRemoteToDeviceWriter.INSTANCE.writeIpPacketToDevice(synAckPacket, this.loop.getKey(),
                    this.loop.getRemoteToDeviceStream());
            this.loop.increaseAccumulateRemoteToDeviceSequenceNumber(1);
            this.loop.setStatus(TcpIoLoopStatus.SYN_RECEIVED);

            return;
        }
        Log.v(TcpIoLoopFlowTask.class.getName(),
                "RECEIVE [SYN], initializing connection, tcp header = " + inputTcpHeader +
                        ", tcp loop = " + this.loop);
        this.loop.markInitializeStarted();
        this.remoteBootstrap
                .connect(this.loop.getDestinationAddress(), this.loop.getDestinationPort()).addListener(
                (ChannelFutureListener) connectResultFuture -> {

                    if (!connectResultFuture.isSuccess()) {
                        Log.e(TcpIoLoopFlowTask.class.getName(),
                                "RECEIVE [SYN], initialize connection FAIL ignore the packet, tcp header ="
                                        + inputTcpHeader + " tcp loop = " + this.loop);
                        IpPacket resetPacket = TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildRstAck(
                                this.loop.getDestinationAddress(),
                                this.loop.getDestinationPort(),
                                this.loop.getSourceAddress(),
                                this.loop.getSourcePort(),
                                this.loop.getAccumulateRemoteToDeviceSequenceNumber(),
                                this.loop.getAccumulateRemoteToDeviceAcknowledgementNumber()
                        );
                        TcpIoLoopRemoteToDeviceWriter.INSTANCE
                                .writeIpPacketToDevice(resetPacket, this.loop.getKey(),
                                        this.loop.getRemoteToDeviceStream());
                        this.makeTcpIoLoopReset();

                        return;
                    }
                    this.loop.increaseAccumulateRemoteToDeviceAcknowledgementNumber(1);
                    this.loop.setRemoteChannel(connectResultFuture.channel());
                    this.loop.getRemoteChannel().attr(TCP_LOOP).setIfAbsent(this.loop);
                    TcpHeaderOption mssOption = null;
                    for (TcpHeaderOption option : inputTcpHeader.getOptions()) {
                        if (option.getKind() == TcpHeaderOption.Kind.MSS) {
                            mssOption = option;
                            break;
                        }
                    }
                    if (mssOption != null) {
                        ByteBuf mssOptionBuf = Unpooled.wrappedBuffer(mssOption.getInfo());
                        int mss = mssOptionBuf.readUnsignedShort();
                        this.loop.setMss(mss);
//                        this.loop.setMss(256);
                    }
                    Log.d(TcpIoLoopFlowTask.class.getName(),
                            "RECEIVE [SYN], initializing connection SUCCESS, switch tcp loop to SYN_RECIVED, tcp header = " +
                                    inputTcpHeader +
                                    ", tcp loop = " + this.loop);
                    if (this.loop.getStatus() == TcpIoLoopStatus.LISTEN) {
//                        this.loop.increaseAccumulateRemoteToDeviceSequenceNumber(1);
                        IpPacket synAckPacket = TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildSynAck(
                                this.loop.getDestinationAddress(),
                                this.loop.getDestinationPort(),
                                this.loop.getSourceAddress(),
                                this.loop.getSourcePort(),
                                this.loop.getAccumulateRemoteToDeviceSequenceNumber(),
                                this.loop.getAccumulateRemoteToDeviceAcknowledgementNumber(),
                                this.loop.getMss()
                        );
                        TcpIoLoopRemoteToDeviceWriter.INSTANCE.writeIpPacketToDevice(synAckPacket, this.loop.getKey(),
                                this.loop.getRemoteToDeviceStream());
                    }
                    this.loop.increaseAccumulateRemoteToDeviceSequenceNumber(1);
                    this.loop.setStatus(TcpIoLoopStatus.SYN_RECEIVED);

                });
    }

    private void doPsh(TcpHeader inputTcpHeader, byte[] data) {
        //Psh ack
        if (data == null) {
            Log.d(TcpIoLoopFlowTask.class.getName(),
                    "RECEIVE [PSH ACK WITHOUT DATA(status=ESTABLISHED, size=0)], tcp header =" +
                            inputTcpHeader +
                            ", tcp loop = " + this.loop);
            return;
        }
        ByteBuf pshDataByteBuf = Unpooled.wrappedBuffer(data);
        if (inputTcpHeader.isFin()) {
            Log.d(TcpIoLoopFlowTask.class.getName(),
                    "RECEIVE [PSH FIN ACK WITH DATA(size=" + data.length +
                            ")], write data to remote, tcp header =" +
                            inputTcpHeader +
                            ", tcp loop = " + this.loop + ", DATA: \n" +
                            ByteBufUtil.prettyHexDump(pshDataByteBuf));
        } else {
            Log.d(TcpIoLoopFlowTask.class.getName(),
                    "RECEIVE [PSH ACK WITH DATA(size=" + data.length +
                            ")], write data to remote, tcp header =" +
                            inputTcpHeader +
                            ", tcp loop = " + this.loop + ", DATA: \n" +
                            ByteBufUtil.prettyHexDump(pshDataByteBuf));
        }
        if (this.loop.getRemoteChannel() == null) {
            IpPacket resetPacket = TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildRstAck(
                    this.loop.getDestinationAddress(),
                    this.loop.getDestinationPort(),
                    this.loop.getSourceAddress(),
                    this.loop.getSourcePort(),
                    this.loop.getAccumulateRemoteToDeviceSequenceNumber(),
                    this.loop.getAccumulateRemoteToDeviceAcknowledgementNumber()
            );
            TcpIoLoopRemoteToDeviceWriter.INSTANCE.writeIpPacketToDevice(resetPacket, this.loop.getKey(),
                    this.loop.getRemoteToDeviceStream());
            this.makeTcpIoLoopReset();
            return;
        }
        this.loop.increaseAccumulateRemoteToDeviceAcknowledgementNumber(data.length);
        this.loop.getRemoteChannel().writeAndFlush(pshDataByteBuf);
    }

    private void doAck(TcpHeader inputTcpHeader, byte[] data) {
        if (TcpIoLoopStatus.SYN_RECEIVED == this.loop.getStatus()) {
            //Should receive the ack of syn_ack.
            this.loop.setStatus(TcpIoLoopStatus.ESTABLISHED);
            Log.d(TcpIoLoopFlowTask.class.getName(),
                    "RECEIVE [ACK], switch tcp loop to ESTABLISHED, tcp header =" + inputTcpHeader +
                            ", tcp loop = " + this.loop);
            return;
        }
        if (TcpIoLoopStatus.ESTABLISHED == this.loop.getStatus()) {
            if (data == null) {
                Log.d(TcpIoLoopFlowTask.class.getName(),
                        "RECEIVE [ACK WITHOUT DATA(status=ESTABLISHED, size=0)], tcp header =" +
                                inputTcpHeader +
                                ", tcp loop = " + this.loop);
                return;
            }
            if (this.loop.getRemoteChannel() == null || !this.loop.getRemoteChannel().isOpen()) {
                Log.e(TcpIoLoopFlowTask.class.getName(),
                        "RECEIVE [ACK WITH DATA(status=ESTABLISHED, size=" + data.length +
                                ")], Fail to write data to remote because of remote channel has problem, tcp header =" +
                                inputTcpHeader +
                                ", tcp loop = " + this.loop);
                IpPacket rstAckPacket = TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildRstAck(
                        this.loop.getDestinationAddress(),
                        this.loop.getDestinationPort(),
                        this.loop.getSourceAddress(),
                        this.loop.getSourcePort(),
                        this.loop.getAccumulateRemoteToDeviceSequenceNumber(),
                        this.loop.getAccumulateRemoteToDeviceAcknowledgementNumber()
                );
                TcpIoLoopRemoteToDeviceWriter.INSTANCE
                        .writeIpPacketToDevice(rstAckPacket, this.loop.getKey(), this.loop.getRemoteToDeviceStream());
                this.makeTcpIoLoopReset();
                return;
            }
            ByteBuf pshDataByteBuf = Unpooled.wrappedBuffer(data);
            Log.d(TcpIoLoopFlowTask.class.getName(),
                    "RECEIVE [ACK WITH DATA(status=ESTABLISHED, size=" + data.length +
                            ")], write data to remote, tcp header =" +
                            inputTcpHeader +
                            ", tcp loop = " + this.loop + ", DATA: \n" + ByteBufUtil.prettyHexDump(pshDataByteBuf));
            if (this.loop.getRemoteChannel() == null) {
                IpPacket resetPacket = TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildRstAck(
                        this.loop.getDestinationAddress(),
                        this.loop.getDestinationPort(),
                        this.loop.getSourceAddress(),
                        this.loop.getSourcePort(),
                        this.loop.getAccumulateRemoteToDeviceSequenceNumber(),
                        this.loop.getAccumulateRemoteToDeviceAcknowledgementNumber()
                );
                TcpIoLoopRemoteToDeviceWriter.INSTANCE.writeIpPacketToDevice(resetPacket, this.loop.getKey(),
                        this.loop.getRemoteToDeviceStream());
                this.makeTcpIoLoopReset();
                return;
            }
            this.loop.increaseAccumulateRemoteToDeviceAcknowledgementNumber(data.length);
            this.loop.getRemoteChannel().writeAndFlush(pshDataByteBuf);
            return;
        }
        if (TcpIoLoopStatus.LAST_ACK == this.loop.getStatus()) {
            Log.d(TcpIoLoopFlowTask.class.getName(),
                    "RECEIVE [ACK(status=LAST_ACK)], close tcp loop, tcp header ="
                            + inputTcpHeader + " tcp loop = " + this.loop);
            this.loop.increaseAccumulateRemoteToDeviceSequenceNumber(1);
            this.delayDestroyTcpIoLoop(DEFAULT_2MSL_TIME);
            return;
        }
        if (TcpIoLoopStatus.CLOSING == this.loop.getStatus()) {
            Log.d(TcpIoLoopFlowTask.class.getName(),
                    "RECEIVE [ACK(status=CLOSING)], switch tcp loop status to TIME_WAITE, tcp header ="
                            + inputTcpHeader + " tcp loop = " + this.loop);
            this.loop.setStatus(TcpIoLoopStatus.TIME_WAITE);
            this.loop.increaseAccumulateRemoteToDeviceAcknowledgementNumber(1);
            timeWaiteTcpIoLoop(() -> {
                Log.d(TcpIoLoopFlowTask.class.getName(),
                        "2MSL TIMER [ACK(status=TIME_WAITE)], send ack and stop tcp loop, send ack, tcp header ="
                                + inputTcpHeader + " tcp loop = " + TcpIoLoopFlowTask.this.loop);
                IpPacket ackPacket =
                        TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildAck(
                                TcpIoLoopFlowTask.this.loop.getDestinationAddress(),
                                TcpIoLoopFlowTask.this.loop.getDestinationPort(),
                                TcpIoLoopFlowTask.this.loop.getSourceAddress(),
                                TcpIoLoopFlowTask.this.loop.getSourcePort(),
                                this.loop.getAccumulateRemoteToDeviceSequenceNumber(),
                                this.loop.getAccumulateRemoteToDeviceAcknowledgementNumber(),
                                null);
                TcpIoLoopRemoteToDeviceWriter.INSTANCE
                        .writeIpPacketToDevice(ackPacket, TcpIoLoopFlowTask.this.loop.getKey(),
                                TcpIoLoopFlowTask.this.loop.getRemoteToDeviceStream());
                this.loop.destroy();
            });
            return;
        }
        if (TcpIoLoopStatus.FIN_WAITE1 == this.loop.getStatus()) {
            Log.d(TcpIoLoopFlowTask.class.getName(),
                    "RECEIVE [ACK(status=FIN_WAITE1)], switch tcp loop status to FIN_WAITE2, tcp header ="
                            + inputTcpHeader + " tcp loop = " + this.loop);
            this.loop.increaseAccumulateRemoteToDeviceSequenceNumber(1);
            this.loop.setStatus(TcpIoLoopStatus.FIN_WAITE2);
            return;
        }
        Log.e(TcpIoLoopFlowTask.class.getName(),
                "RECEIVE [ACK(status=" + this.loop.getStatus() + ", size=" + (data == null ? 0 : data.length) +
                        ")], Tcp loop in illegal state, ignore current packet do nothing, tcp header ="
                        + inputTcpHeader + " tcp loop = " + this.loop);
    }

    private void makeTcpIoLoopReset() {
        this.loop.reset();
    }

    private void timeWaiteTcpIoLoop(Runnable doSomeThing) {
        synchronized (this.loop) {
            this.loop.setStatus(TcpIoLoopStatus.TIME_WAITE);
            this.twoMslTimerExecutor.schedule(() -> {
                doSomeThing.run();
                loop.destroy();
            }, DEFAULT_2MSL_TIME, TimeUnit.SECONDS);
        }
    }

    private void delayDestroyTcpIoLoop(Integer delayTime) {
        Integer delay = delayTime;
        if (delay == null) {
            delay = DEFAULT_DELAY_TIME;
        }
        this.loop.destroy();
        synchronized (this.loop) {
            this.twoMslTimerExecutor.schedule(loop.getFlowTask()::stop, delay, TimeUnit.SECONDS);
        }
    }

    private void doRst(TcpHeader inputTcpHeader) {
        Log.d(TcpIoLoopFlowTask.class.getName(),
                "RECEIVE [RST], destroy tcp loop, tcp header =" +
                        inputTcpHeader +
                        ", tcp loop = " + this.loop);
        this.delayDestroyTcpIoLoop(null);
    }

    private void doFin(TcpHeader inputTcpHeader) {
        if (TcpIoLoopStatus.ESTABLISHED == this.loop.getStatus() && inputTcpHeader.isAck()) {
            this.loop.setStatus(TcpIoLoopStatus.CLOSE_WAIT);
            Log.d(TcpIoLoopFlowTask.class.getName(),
                    "RECEIVE [FIN(status=ESTABLISHED, STEP1)], switch tcp loop status to CLOSE_WAIT, tcp header =" +
                            inputTcpHeader +
                            ", tcp loop = " + this.loop);
            this.loop.getRemoteChannel().flush();
            this.loop.getRemoteChannel().close().addListener(future -> {
                synchronized (this.loop) {
                    this.loop.increaseAccumulateRemoteToDeviceAcknowledgementNumber(1);
                    IpPacket ackIpPacket = TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildAck(
                            this.loop.getDestinationAddress(),
                            this.loop.getDestinationPort(),
                            this.loop.getSourceAddress(),
                            this.loop.getSourcePort(),
                            this.loop.getAccumulateRemoteToDeviceSequenceNumber(),
                            this.loop.getAccumulateRemoteToDeviceAcknowledgementNumber(),
                            null);
                    TcpIoLoopRemoteToDeviceWriter.INSTANCE
                            .writeIpPacketToDevice(ackIpPacket, this.loop.getKey(),
                                    this.loop.getRemoteToDeviceStream());
                    Log.d(TcpIoLoopFlowTask.class.getName(),
                            "RECEIVE [FIN(status=ESTABLISHED, STEP2)], switch tcp loop status to LAST_ACK, tcp header =" +
                                    inputTcpHeader +
                                    ", tcp loop = " + this.loop);
                    IpPacket finIpPacket = TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildFin(
                            this.loop.getDestinationAddress(),
                            this.loop.getDestinationPort(),
                            this.loop.getSourceAddress(),
                            this.loop.getSourcePort(),
                            this.loop.getAccumulateRemoteToDeviceSequenceNumber(),
                            this.loop.getAccumulateRemoteToDeviceAcknowledgementNumber());
                    TcpIoLoopRemoteToDeviceWriter.INSTANCE
                            .writeIpPacketToDevice(finIpPacket, this.loop.getKey(),
                                    this.loop.getRemoteToDeviceStream());
                    this.loop.setStatus(TcpIoLoopStatus.LAST_ACK);
                    this.delayDestroyTcpIoLoop(DEFAULT_2MSL_TIME + 10);
                }
            });
            return;
        }
        if (TcpIoLoopStatus.CLOSING == this.loop.getStatus() && inputTcpHeader.isAck()) {
            Log.d(TcpIoLoopFlowTask.class.getName(),
                    "RECEIVE [FIN ACK(status=CLOSING)], switch tcp loop status to TIME_WAITE, send ack, tcp header ="
                            + inputTcpHeader + " tcp loop = " + this.loop);
            this.loop.increaseAccumulateRemoteToDeviceAcknowledgementNumber(1);
            timeWaiteTcpIoLoop(() -> {
                Log.d(TcpIoLoopFlowTask.class.getName(),
                        "2MSL TIMER [FIN ACK(status=TIME_WAITE)], send ack and stop tcp loop, send ack, tcp header ="
                                + inputTcpHeader + " tcp loop = " + TcpIoLoopFlowTask.this.loop);
                IpPacket ackPacket =
                        TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildAck(
                                TcpIoLoopFlowTask.this.loop.getDestinationAddress(),
                                TcpIoLoopFlowTask.this.loop.getDestinationPort(),
                                TcpIoLoopFlowTask.this.loop.getSourceAddress(),
                                TcpIoLoopFlowTask.this.loop.getSourcePort(),
                                this.loop.getAccumulateRemoteToDeviceSequenceNumber(),
                                this.loop.getAccumulateRemoteToDeviceAcknowledgementNumber(),
                                null);
                TcpIoLoopRemoteToDeviceWriter.INSTANCE
                        .writeIpPacketToDevice(ackPacket, TcpIoLoopFlowTask.this.loop.getKey(),
                                TcpIoLoopFlowTask.this.loop.getRemoteToDeviceStream());
                this.loop.destroy();
            });
            return;
        }
        if (TcpIoLoopStatus.CLOSE_WAIT == this.loop.getStatus()) {
            this.loop.setStatus(TcpIoLoopStatus.CLOSED);
            this.delayDestroyTcpIoLoop(null);
            return;
        }
        if (TcpIoLoopStatus.FIN_WAITE1 == this.loop.getStatus() && inputTcpHeader.isAck()) {
            Log.d(TcpIoLoopFlowTask.class.getName(),
                    "RECEIVE [FIN ACK(status=FIN_WAITE1)], switch tcp loop status to CLOSING, tcp header ="
                            + inputTcpHeader + " tcp loop = " + this.loop);
            this.loop.increaseAccumulateRemoteToDeviceSequenceNumber(1);
            this.loop.setStatus(TcpIoLoopStatus.CLOSING);
            return;
        }
        if (TcpIoLoopStatus.FIN_WAITE2 == this.loop.getStatus() && inputTcpHeader.isAck()) {
            Log.d(TcpIoLoopFlowTask.class.getName(),
                    "RECEIVE [FIN(status=FIN_WAITE2)], switch tcp loop status to TIME_WAITE, send ack, tcp header ="
                            + inputTcpHeader + " tcp loop = " + this.loop);
            this.loop.increaseAccumulateRemoteToDeviceAcknowledgementNumber(1);
            timeWaiteTcpIoLoop(() -> {
                Log.d(TcpIoLoopFlowTask.class.getName(),
                        "2MSL TIMER [FIN ACK(status=TIME_WAITE)], send ack and stop tcp loop, send ack, tcp header ="
                                + inputTcpHeader + " tcp loop = " + TcpIoLoopFlowTask.this.loop);
                IpPacket ackPacket =
                        TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildAck(
                                TcpIoLoopFlowTask.this.loop.getDestinationAddress(),
                                TcpIoLoopFlowTask.this.loop.getDestinationPort(),
                                TcpIoLoopFlowTask.this.loop.getSourceAddress(),
                                TcpIoLoopFlowTask.this.loop.getSourcePort(),
                                this.loop.getAccumulateRemoteToDeviceSequenceNumber(),
                                this.loop.getAccumulateRemoteToDeviceAcknowledgementNumber(),
                                null);
                TcpIoLoopRemoteToDeviceWriter.INSTANCE
                        .writeIpPacketToDevice(ackPacket, TcpIoLoopFlowTask.this.loop.getKey(),
                                TcpIoLoopFlowTask.this.loop.getRemoteToDeviceStream());
                this.loop.destroy();
            });
            return;
        }
        Log.e(TcpIoLoopFlowTask.class.getName(),
                "RECEIVE [FIN(status=" + this.loop.getStatus() +
                        ")], Tcp loop in illegal state, ignore current packet do nothing, tcp header ="
                        + inputTcpHeader + " tcp loop = " + this.loop);
    }
}
