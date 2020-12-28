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

import java.util.Timer;
import java.util.TimerTask;

import static com.ppaass.agent.android.io.process.tcp.ITcpIoLoopConstant.TCP_LOOP;

class TcpIoLoopFlowTask implements Runnable {
    private static final int DEFAULT_2MSL_TIME = 1000 * 120;
    private final TcpIoLoop loop;
    private final Bootstrap remoteBootstrap;
    private boolean alive;
    private final Object waitToReadMore;

    public TcpIoLoopFlowTask(TcpIoLoop loop, Bootstrap remoteBootstrap) {
        this.loop = loop;
        this.remoteBootstrap = remoteBootstrap;
        this.alive = true;
        this.waitToReadMore = new Object();
    }

    public synchronized void stop() {
        Log.d(TcpIoLoopFlowTask.class.getName(),
                "Stop tcp loop device to remote task, tcp loop = " + this.loop);
        this.alive = false;
        synchronized (this.waitToReadMore) {
            this.waitToReadMore.notifyAll();
        }
    }

    public void resumeToReadMore() {
        synchronized (this.waitToReadMore) {
            this.waitToReadMore.notifyAll();
        }
    }

    @Override
    public void run() {
        while (this.alive) {
            IpPacket inputIpPacket = this.loop.pollDeviceToRemoteIpPacket();
            if (inputIpPacket == null) {
                synchronized (this.waitToReadMore) {
                    try {
                        Log.d(TcpIoLoopFlowTask.class.getName(), "Flow thread PAUSED, tcp loop = " + this.loop);
                        this.waitToReadMore.wait();
                        Log.d(TcpIoLoopFlowTask.class.getName(), "Flow thread RESUMED, tcp loop = " + this.loop);
                    } catch (InterruptedException e) {
                        Log.e(TcpIoLoopFlowTask.class.getName(),
                                "Fail to pause tcp loop flow thread because of exception, tcp loop = " + this.loop,
                                e);
                        continue;
                    }
                }
                continue;
            }
            synchronized (this.loop) {
                TcpPacket inputTcpPacket = (TcpPacket) inputIpPacket.getData();
                TcpHeader inputTcpHeader = inputTcpPacket.getHeader();
                this.loop.setWindowSizeInByte(inputTcpHeader.getWindow());
                if (TcpIoLoopStatus.RESET == this.loop.getStatus()) {
                    Log.d(TcpIoLoopFlowTask.class.getName(),
                            "Ignore the incoming ip packet as tcp loop is reset already, tcp header = " +
                                    inputTcpHeader +
                                    ", tcp loop = " + this.loop);
                    continue;
                }
                if (TcpIoLoopStatus.CLOSED == this.loop.getStatus()) {
                    Log.d(TcpIoLoopFlowTask.class.getName(),
                            "Ignore the incoming ip packet as tcp loop is closed already, tcp header = " +
                                    inputTcpHeader +
                                    ", tcp loop = " + this.loop);
                    continue;
                }
                if (TcpIoLoopStatus.TIME_WAITE == this.loop.getStatus()) {
                    Log.d(TcpIoLoopFlowTask.class.getName(),
                            "Ignore the incoming ip packet as tcp loop is time waite already, tcp header = " +
                                    inputTcpHeader +
                                    ", tcp loop = " + this.loop);
                    continue;
                }
                if (inputTcpHeader.isSyn()) {
                    doSyn(inputTcpHeader);
                    continue;
                }
                if (inputTcpHeader.isPsh()) {
                    byte[] inputData = inputTcpPacket.getData();
                    if (inputData != null && inputData.length == 0) {
                        inputData = null;
                    }
                    doPsh(inputTcpHeader, inputData);
                    continue;
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
                    continue;
                }
                if (inputTcpHeader.isRst()) {
                    doRst(inputTcpHeader);
                }
            }
        }
    }

    private void doSyn(TcpHeader inputTcpHeader) {
        if (TcpIoLoopStatus.LISTEN != this.loop.getStatus()) {
            Log.e(TcpIoLoopFlowTask.class.getName(),
                    "Tcp loop is NOT in LISTEN status, return RST back to device, tcp header = " + inputTcpHeader +
                            ", tcp loop = " + this.loop);
            this.loop.setExpectDeviceSequence(inputTcpHeader.getSequenceNumber());
            this.loop.setRemoteSequence(inputTcpHeader.getAcknowledgementNumber());
            this.loop.setStatus(TcpIoLoopStatus.RESET);
            IpPacket rstAckPacket = TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildRstAck(this.loop);
            TcpIoLoopRemoteToDeviceWriter.INSTANCE
                    .writeIpPacketToDevice(rstAckPacket, this.loop.getKey(), this.loop.getRemoteToDeviceStream());
            return;
        }
        Log.v(TcpIoLoopFlowTask.class.getName(),
                "RECEIVE [SYN], initializing connection, tcp header = " + inputTcpHeader +
                        ", tcp loop = " + this.loop);
        this.remoteBootstrap
                .connect(this.loop.getDestinationAddress(), this.loop.getDestinationPort()).addListener(
                (ChannelFutureListener) connectResultFuture -> {
                    if (!connectResultFuture.isSuccess()) {
                        Log.e(TcpIoLoopFlowTask.class.getName(),
                                "RECEIVE [SYN], initialize connection FAIL ignore the packet, tcp header ="
                                        + inputTcpHeader + " tcp loop = " + this.loop);
                        this.loop.destroy();
                        return;
                    }
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
//                        tcpIoLoop.setMss(mss);
                        this.loop.setMss(256);
                    }
                    this.loop.setWindowSizeInByte(inputTcpHeader.getWindow());
                    this.loop.setStatus(TcpIoLoopStatus.SYN_RECEIVED);
                    this.loop.setExpectDeviceSequence(inputTcpHeader.getSequenceNumber() + 1);
                    this.loop.setRemoteSequence(this.loop.getBaseRemoteSequence());
                    this.loop.setBaseDeviceSequence(inputTcpHeader.getSequenceNumber());
                    Log.d(TcpIoLoopFlowTask.class.getName(),
                            "RECEIVE [SYN], initializing connection SUCCESS, switch tcp loop to SYN_RECIVED, tcp header = " +
                                    inputTcpHeader +
                                    ", tcp loop = " + this.loop);
                    IpPacket synAckPacket = TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildSynAck(this.loop);
                    TcpIoLoopRemoteToDeviceWriter.INSTANCE.writeIpPacketToDevice(synAckPacket, this.loop.getKey(),
                            this.loop.getRemoteToDeviceStream());
                });
    }

    private void doPsh(TcpHeader inputTcpHeader, byte[] data) {
        //Psh ack
        if (data == null) {
            this.loop.setExpectDeviceSequence(inputTcpHeader.getSequenceNumber());
            this.loop.setRemoteSequence(inputTcpHeader.getAcknowledgementNumber());
            Log.d(TcpIoLoopFlowTask.class.getName(),
                    "RECEIVE [PSH ACK WITHOUT DATA(size=0)], No data to remote ack to device, tcp header =" +
                            inputTcpHeader +
                            ", tcp loop = " + this.loop);
//            if (this.loop.getWindowSizeInByte() == 0) {
//                this.loop.getWindowTask().pause();
//            } else {
//                this.loop.getWindowTask().resume();
//            }
            this.loop.getWindowTask().resume();
            return;
        }
        this.loop.setRemoteSequence(inputTcpHeader.getAcknowledgementNumber());
        this.loop.setExpectDeviceSequence(inputTcpHeader.getSequenceNumber() + data.length);
        if (this.loop.getRemoteChannel() == null || !this.loop.getRemoteChannel().isOpen()) {
            Log.e(TcpIoLoopFlowTask.class.getName(),
                    "RECEIVE [PSH ACK WITH DATA(size=" + data.length +
                            ")], Fail to write data to remote because of remote channel has problem, tcp header =" +
                            inputTcpHeader +
                            ", tcp loop = " + this.loop);
            this.loop.setStatus(TcpIoLoopStatus.RESET);
            IpPacket rstAckPacket = TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildRstAck(this.loop);
            TcpIoLoopRemoteToDeviceWriter.INSTANCE
                    .writeIpPacketToDevice(rstAckPacket, this.loop.getKey(), this.loop.getRemoteToDeviceStream());
            return;
        }
        ByteBuf pshDataByteBuf = Unpooled.wrappedBuffer(data);
        Log.d(TcpIoLoopFlowTask.class.getName(),
                "RECEIVE [PSH ACK WITH DATA(size=" + data.length +
                        ")], write data to remote, tcp header =" +
                        inputTcpHeader +
                        ", tcp loop = " + this.loop + ", DATA: \n" +
                        ByteBufUtil.prettyHexDump(pshDataByteBuf));
//        IpPacket ackPacket = TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildPshAck(this.loop, null);
//        TcpIoLoopRemoteToDeviceWriter.INSTANCE
//                .writeIpPacketToDevice(ackPacket, this.loop.getKey(), this.loop.getRemoteToDeviceStream());
//        this.loop.getWindowTask().pause();
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
                this.loop.setRemoteSequence(inputTcpHeader.getAcknowledgementNumber());
                this.loop.setExpectDeviceSequence(inputTcpHeader.getSequenceNumber());
                Log.d(TcpIoLoopFlowTask.class.getName(),
                        "RECEIVE [ACK WITHOUT DATA(status=ESTABLISHED, size=0)], No data to remote ack to device, " +
                                "release the lock continue push data to device from remote, tcp header =" +
                                inputTcpHeader +
                                ", tcp loop = " + this.loop);
//                if (this.loop.getWindowSizeInByte() == 0) {
//                    this.loop.getWindowTask().pause();
//                } else {
//                    this.loop.getWindowTask().resume();
//                }
                this.loop.getWindowTask().resume();
                return;
            }
            this.loop.setRemoteSequence(inputTcpHeader.getAcknowledgementNumber());
            this.loop.setExpectDeviceSequence(inputTcpHeader.getSequenceNumber() + data.length);
            if (this.loop.getRemoteChannel() == null || !this.loop.getRemoteChannel().isOpen()) {
                Log.e(TcpIoLoopFlowTask.class.getName(),
                        "RECEIVE [ACK WITH DATA(status=ESTABLISHED, size=" + data.length +
                                ")], Fail to write data to remote because of remote channel has problem, tcp header =" +
                                inputTcpHeader +
                                ", tcp loop = " + this.loop);
                this.loop.setStatus(TcpIoLoopStatus.RESET);
                IpPacket rstAckPacket = TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildRstAck(this.loop);
                TcpIoLoopRemoteToDeviceWriter.INSTANCE
                        .writeIpPacketToDevice(rstAckPacket, this.loop.getKey(), this.loop.getRemoteToDeviceStream());
                return;
            }
            ByteBuf pshDataByteBuf = Unpooled.wrappedBuffer(data);
            Log.d(TcpIoLoopFlowTask.class.getName(),
                    "RECEIVE [ACK WITH DATA(status=ESTABLISHED, size=" + data.length +
                            ")], write data to remote, tcp header =" +
                            inputTcpHeader +
                            ", tcp loop = " + this.loop + ", DATA: \n" + ByteBufUtil.prettyHexDump(pshDataByteBuf));
//            IpPacket ackPacket = TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildPshAck(this.loop, null);
//            TcpIoLoopRemoteToDeviceWriter.INSTANCE
//                    .writeIpPacketToDevice(ackPacket, this.loop.getKey(), this.loop.getRemoteToDeviceStream());
//            this.loop.getWindowTask().pause();
            this.loop.getRemoteChannel().writeAndFlush(pshDataByteBuf);
            return;
        }
        if (TcpIoLoopStatus.FIN_WAITE1 == this.loop.getStatus()) {
            Log.d(TcpIoLoopFlowTask.class.getName(),
                    "RECEIVE [ACK(status=FIN_WAITE1)], switch tcp loop status to FIN_WAITE2, tcp header ="
                            + inputTcpHeader + " tcp loop = " + this.loop);
            this.loop.setStatus(TcpIoLoopStatus.FIN_WAITE2);
            this.loop.destroy();
            return;
        }
        if (TcpIoLoopStatus.LAST_ACK == this.loop.getStatus()) {
            this.loop.setStatus(TcpIoLoopStatus.CLOSED);
            Log.d(TcpIoLoopFlowTask.class.getName(),
                    "RECEIVE [ACK(status=LAST_ACK)], close tcp loop, tcp header ="
                            + inputTcpHeader + " tcp loop = " + this.loop);
            this.loop.destroy();
            return;
        }
        Log.e(TcpIoLoopFlowTask.class.getName(),
                "RECEIVE [ACK], Tcp loop in illegal state, send RST back to device, tcp header ="
                        + inputTcpHeader + " tcp loop = " + this.loop);
        this.loop.setRemoteSequence(inputTcpHeader.getAcknowledgementNumber());
        this.loop.setExpectDeviceSequence(inputTcpHeader.getSequenceNumber());
        this.loop.setStatus(TcpIoLoopStatus.RESET);
        IpPacket rstAckPacket = TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildRstAck(this.loop);
        TcpIoLoopRemoteToDeviceWriter.INSTANCE
                .writeIpPacketToDevice(rstAckPacket, this.loop.getKey(), this.loop.getRemoteToDeviceStream());
//        tcpIoLoop.destroy();
    }

    private void doRst(TcpHeader inputTcpHeader) {
        Log.d(TcpIoLoopFlowTask.class.getName(),
                "RECEIVE [RST], destroy tcp loop, tcp header =" +
                        inputTcpHeader +
                        ", tcp loop = " + this.loop);
        this.loop.destroy();
    }

    private void doFin(TcpHeader inputTcpHeader) {
        if (TcpIoLoopStatus.ESTABLISHED == this.loop.getStatus()) {
            this.loop.setStatus(TcpIoLoopStatus.CLOSE_WAIT);
            this.loop.setRemoteSequence(inputTcpHeader.getAcknowledgementNumber());
            this.loop.setExpectDeviceSequence(inputTcpHeader.getSequenceNumber() + 1);
            Log.d(TcpIoLoopFlowTask.class.getName(),
                    "RECEIVE [FIN(status=ESTABLISHED, STEP1)], switch tcp loop status to CLOSE_WAIT, tcp header =" +
                            inputTcpHeader +
                            ", tcp loop = " + this.loop);
            IpPacket ackIpPacket = TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildAck(this.loop, null);
            TcpIoLoopRemoteToDeviceWriter.INSTANCE
                    .writeIpPacketToDevice(ackIpPacket, this.loop.getKey(), this.loop.getRemoteToDeviceStream());
            this.loop.setStatus(TcpIoLoopStatus.LAST_ACK);
//            try {
//                tcpIoLoopInfo.getAckSemaphore().acquire();
//            } catch (InterruptedException e) {
//                Log.e(TcpIoLoop.class.getName(),
//                        "RECEIVE [FIN(status=ESTABLISHED, STEP1->STEP2 ERROR)], exception when switch status from CLOSE_WAIT to LAST_ACK, tcp header =" +
//                                inputTcpHeader +
//                                ", tcp loop = " + tcpIoLoopInfo, e);
//            }
            IpPacket finPacket = TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildFin(this.loop);
            TcpIoLoopRemoteToDeviceWriter.INSTANCE
                    .writeIpPacketToDevice(finPacket, this.loop.getKey(), this.loop.getRemoteToDeviceStream());
            Log.d(TcpIoLoopFlowTask.class.getName(),
                    "RECEIVE [FIN(status=ESTABLISHED, STEP2)], switch tcp loop status to LAST_ACK, tcp header =" +
                            inputTcpHeader +
                            ", tcp loop = " + this.loop);
            return;
        }
        if (TcpIoLoopStatus.FIN_WAITE1 == this.loop.getStatus()) {
//            tcpIoLoopInfo.getAckSemaphore().release();
            this.loop.setRemoteSequence(inputTcpHeader.getAcknowledgementNumber());
            this.loop.setExpectDeviceSequence(inputTcpHeader.getSequenceNumber());
            if (inputTcpHeader.isAck()) {
                Log.d(TcpIoLoopFlowTask.class.getName(),
                        "RECEIVE [FIN ACK(status=FIN_WAITE1)], close tcp loop, tcp header ="
                                + inputTcpHeader + " tcp loop = " + this.loop);
                this.loop.setStatus(TcpIoLoopStatus.TIME_WAITE);
                this.loop.destroy();
                return;
            } else {
                Log.d(TcpIoLoopFlowTask.class.getName(),
                        "RECEIVE [FIN(status=FIN_WAITE1)], switch tcp loop status to FIN_WAITE2, send ack, tcp header ="
                                + inputTcpHeader + " tcp loop = " + this.loop);
            }
            this.loop.setStatus(TcpIoLoopStatus.FIN_WAITE2);
            return;
        }
        if (TcpIoLoopStatus.FIN_WAITE2 == this.loop.getStatus()) {
            Log.d(TcpIoLoopFlowTask.class.getName(),
                    "RECEIVE [FIN(status=FIN_WAITE2)], switch tcp loop status to TIME_WAITE, send ack, tcp header ="
                            + inputTcpHeader + " tcp loop = " + this.loop);
            long ackIn2MslTimer = inputTcpHeader.getSequenceNumber() + 1;
            long seqIn2MslTimer = inputTcpHeader.getAcknowledgementNumber();
            this.loop.setStatus(TcpIoLoopStatus.TIME_WAITE);
            Timer twoMslTimer = new Timer();
            twoMslTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    TcpIoLoopFlowTask.this.loop.setExpectDeviceSequence(ackIn2MslTimer);
                    TcpIoLoopFlowTask.this.loop.setRemoteSequence(seqIn2MslTimer);
                    Log.d(TcpIoLoopFlowTask.class.getName(),
                            "2MSL TIMER [ACK(status=TIME_WAITE)], send ack and stop tcp loop, send ack, tcp header ="
                                    + inputTcpHeader + " tcp loop = " + TcpIoLoopFlowTask.this.loop);
                    IpPacket ackPacket =
                            TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildAck(TcpIoLoopFlowTask.this.loop, null);
                    TcpIoLoopRemoteToDeviceWriter.INSTANCE
                            .writeIpPacketToDevice(ackPacket, TcpIoLoopFlowTask.this.loop.getKey(),
                                    TcpIoLoopFlowTask.this.loop.getRemoteToDeviceStream());
                    TcpIoLoopFlowTask.this.loop.destroy();
                }
            }, DEFAULT_2MSL_TIME);
            return;
        }
        Log.e(TcpIoLoopFlowTask.class.getName(),
                "RECEIVE [FIN] Tcp loop in illegal state, send RST back to device, tcp header ="
                        + inputTcpHeader + " tcp loop = " + this.loop);
        this.loop.setExpectDeviceSequence(inputTcpHeader.getSequenceNumber());
        this.loop.setRemoteSequence(inputTcpHeader.getAcknowledgementNumber());
        this.loop.setStatus(TcpIoLoopStatus.RESET);
        IpPacket rstAckPacket = TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildRstAck(this.loop);
        TcpIoLoopRemoteToDeviceWriter.INSTANCE
                .writeIpPacketToDevice(rstAckPacket, this.loop.getKey(), this.loop.getRemoteToDeviceStream());
        this.loop.destroy();
    }
}
