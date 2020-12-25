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

class TcpIoLoopDeviceToRemoteTask implements Runnable {
    private static final int DEFAULT_2MSL_TIME = 1000 * 120;
    private final TcpIoLoopInfo loopInfo;
    private final Bootstrap remoteBootstrap;
    private boolean alive;

    public TcpIoLoopDeviceToRemoteTask(TcpIoLoopInfo loopInfo, Bootstrap remoteBootstrap) {
        this.loopInfo = loopInfo;
        this.remoteBootstrap = remoteBootstrap;
        this.alive = true;
    }

    public synchronized void stop() {
        Log.d(TcpIoLoopRemoteToDeviceTask.class.getName(),
                "Stop tcp loop device to remote task, tcp loop = " + this.loopInfo);
        this.alive = false;
    }

    @Override
    public void run() {
        while (this.alive) {
            IpPacket inputIpPacket = this.loopInfo.pollDeviceToRemoteIpPacket();
            if (inputIpPacket == null) {
                continue;
            }
            TcpPacket inputTcpPacket = (TcpPacket) inputIpPacket.getData();
            TcpHeader inputTcpHeader = inputTcpPacket.getHeader();
            if (TcpIoLoopStatus.RESET == this.loopInfo.getStatus()) {
                Log.d(TcpIoLoopDeviceToRemoteTask.class.getName(),
                        "Ignore the incoming ip packet as tcp loop is reset already, tcp header = " + inputTcpHeader +
                                ", tcp loop = " + this.loopInfo);
                continue;
            }
            if (TcpIoLoopStatus.CLOSED == this.loopInfo.getStatus()) {
                Log.d(TcpIoLoopDeviceToRemoteTask.class.getName(),
                        "Ignore the incoming ip packet as tcp loop is closed already, tcp header = " + inputTcpHeader +
                                ", tcp loop = " + this.loopInfo);
                continue;
            }
            if (TcpIoLoopStatus.TIME_WAITE == this.loopInfo.getStatus()) {
                Log.d(TcpIoLoopDeviceToRemoteTask.class.getName(),
                        "Ignore the incoming ip packet as tcp loop is time waite already, tcp header = " +
                                inputTcpHeader +
                                ", tcp loop = " + this.loopInfo);
                continue;
            }
            this.loopInfo.setLatestMessageTime(System.nanoTime());
            if (inputTcpHeader.isSyn()) {
                doSyn(this.loopInfo, inputTcpHeader);
                continue;
            }
            if (inputTcpHeader.isPsh()) {
                byte[] inputData = inputTcpPacket.getData();
                if (inputData != null && inputData.length == 0) {
                    inputData = null;
                }
                doPsh(this.loopInfo, inputTcpHeader, inputData);
                continue;
            }
            if ((inputTcpHeader.isFin())) {
                doFin(this.loopInfo, inputTcpHeader);
                return;
            }
            if (inputTcpHeader.isAck()) {
                byte[] inputData = inputTcpPacket.getData();
                if (inputData != null && inputData.length == 0) {
                    inputData = null;
                }
                doAck(this.loopInfo, inputTcpHeader, inputData);
                continue;
            }
            if (inputTcpHeader.isRst()) {
                doRst(this.loopInfo, inputTcpHeader);
            }
        }
    }

    private void doSyn(TcpIoLoopInfo tcpIoLoopInfo, TcpHeader inputTcpHeader) {
        if (TcpIoLoopStatus.LISTEN != tcpIoLoopInfo.getStatus()) {
            Log.e(TcpIoLoopDeviceToRemoteTask.class.getName(),
                    "Tcp loop is NOT in LISTEN status, return RST back to device, tcp header = " + inputTcpHeader +
                            ", tcp loop = " + tcpIoLoopInfo);
            tcpIoLoopInfo.setCurrentRemoteToDeviceAck(inputTcpHeader.getSequenceNumber());
            tcpIoLoopInfo.setCurrentRemoteToDeviceSeq(inputTcpHeader.getAcknowledgementNumber());
            tcpIoLoopInfo.setStatus(TcpIoLoopStatus.RESET);
            TcpIoLoopOutputWriter.INSTANCE.writeRstAckToQueue(this.loopInfo);
            return;
        }
        Log.v(TcpIoLoopDeviceToRemoteTask.class.getName(),
                "RECEIVE [SYN], initializing connection, tcp header = " + inputTcpHeader +
                        ", tcp loop = " + tcpIoLoopInfo);
        this.remoteBootstrap
                .connect(tcpIoLoopInfo.getDestinationAddress(), tcpIoLoopInfo.getDestinationPort()).addListener(
                (ChannelFutureListener) connectResultFuture -> {
                    if (!connectResultFuture.isSuccess()) {
                        Log.e(TcpIoLoopDeviceToRemoteTask.class.getName(),
                                "RECEIVE [SYN], initialize connection FAIL send RST back to device, tcp header ="
                                        + inputTcpHeader + " tcp loop = " + tcpIoLoopInfo);
                        tcpIoLoopInfo.setCurrentRemoteToDeviceAck(inputTcpHeader.getSequenceNumber());
                        tcpIoLoopInfo.setCurrentRemoteToDeviceSeq(inputTcpHeader.getAcknowledgementNumber());
                        tcpIoLoopInfo.setStatus(TcpIoLoopStatus.RESET);
                        TcpIoLoopOutputWriter.INSTANCE.writeRstAckToQueue(tcpIoLoopInfo);
                        return;
                    }
                    tcpIoLoopInfo.setRemoteChannel(connectResultFuture.channel());
                    tcpIoLoopInfo.getRemoteChannel().attr(TCP_LOOP).setIfAbsent(tcpIoLoopInfo);
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
                        tcpIoLoopInfo.setMss(mss);
                    }
                    tcpIoLoopInfo.setWindow(inputTcpHeader.getWindow());
                    tcpIoLoopInfo.setStatus(TcpIoLoopStatus.SYN_RECEIVED);
                    tcpIoLoopInfo.setCurrentRemoteToDeviceSeq(tcpIoLoopInfo.getBaseLoopSequence());
                    tcpIoLoopInfo.setCurrentRemoteToDeviceAck(inputTcpHeader.getSequenceNumber() + 1);
                    Log.d(TcpIoLoopDeviceToRemoteTask.class.getName(),
                            "RECEIVE [SYN], initializing connection success, switch tcp loop to SYN_RECIVED, tcp header = " +
                                    inputTcpHeader +
                                    ", tcp loop = " + tcpIoLoopInfo);
                    TcpIoLoopOutputWriter.INSTANCE.writeSynAckToQueue(tcpIoLoopInfo);
                });
    }

    private void doPsh(TcpIoLoopInfo tcpIoLoopInfo, TcpHeader inputTcpHeader, byte[] data) {
        //Psh ack
        tcpIoLoopInfo.setCurrentRemoteToDeviceSeq(inputTcpHeader.getAcknowledgementNumber());
        if (data == null) {
            tcpIoLoopInfo.setCurrentRemoteToDeviceAck(inputTcpHeader.getSequenceNumber());
            Log.d(TcpIoLoopDeviceToRemoteTask.class.getName(),
                    "RECEIVE [PSH ACK WITHOUT DATA(size=0)], No data to remote ack to device, tcp header =" +
                            inputTcpHeader +
                            ", tcp loop = " + tcpIoLoopInfo);
//                    TcpIoLoopOutputWriter.INSTANCE.writeAck(tcpIoLoop, null, this.remoteToDeviceStream);
            tcpIoLoopInfo.getExchangeSemaphore().release();
            return;
        }
        tcpIoLoopInfo.setCurrentRemoteToDeviceAck(inputTcpHeader.getSequenceNumber() + data.length);
        if (tcpIoLoopInfo.getRemoteChannel() == null || !tcpIoLoopInfo.getRemoteChannel().isOpen()) {
            Log.e(TcpIoLoopDeviceToRemoteTask.class.getName(),
                    "RECEIVE [PSH ACK WITH DATA(size=" + data.length +
                            ")], Fail to write data to remote because of remote channel has problem, tcp header =" +
                            inputTcpHeader +
                            ", tcp loop = " + tcpIoLoopInfo);
            tcpIoLoopInfo.setStatus(TcpIoLoopStatus.RESET);
            TcpIoLoopOutputWriter.INSTANCE.writeRstAckToQueue(tcpIoLoopInfo);
            return;
        }
        ByteBuf pshDataByteBuf = Unpooled.wrappedBuffer(data);
        Log.d(TcpIoLoopDeviceToRemoteTask.class.getName(),
                "RECEIVE [PSH ACK WITH DATA(size=" + data.length +
                        ")], write data to remote, tcp header =" +
                        inputTcpHeader +
                        ", tcp loop = " + tcpIoLoopInfo + ", DATA: \n" +
                        ByteBufUtil.prettyHexDump(pshDataByteBuf));
        tcpIoLoopInfo.getRemoteChannel().writeAndFlush(pshDataByteBuf);
    }

    private void doAck(TcpIoLoopInfo tcpIoLoopInfo, TcpHeader inputTcpHeader, byte[] data) {
        if (TcpIoLoopStatus.SYN_RECEIVED == tcpIoLoopInfo.getStatus()) {
            //Should receive the ack of syn_ack.
            tcpIoLoopInfo.setStatus(TcpIoLoopStatus.ESTABLISHED);
            Log.d(TcpIoLoopDeviceToRemoteTask.class.getName(),
                    "RECEIVE [ACK], switch tcp loop to ESTABLISHED, tcp header =" + inputTcpHeader +
                            ", tcp loop = " + tcpIoLoopInfo);
            return;
        }
        if (TcpIoLoopStatus.ESTABLISHED == tcpIoLoopInfo.getStatus()) {
            tcpIoLoopInfo.setCurrentRemoteToDeviceSeq(inputTcpHeader.getAcknowledgementNumber());
            if (data == null) {
                tcpIoLoopInfo.setCurrentRemoteToDeviceAck(inputTcpHeader.getSequenceNumber());
//                if (tcpIoLoop.getCurrentRemoteToDeviceAck() - inputTcpHeader.getSequenceNumber() <
//                        tcpIoLoop.getWindow()) {
                Log.d(TcpIoLoopDeviceToRemoteTask.class.getName(),
                        "RECEIVE [ACK WITHOUT DATA(status=ESTABLISHED, size=0, win_diff = " +
                                (tcpIoLoopInfo.getCurrentRemoteToDeviceAck() - inputTcpHeader.getSequenceNumber()) +
                                ")], No data to remote ack to device, win_diff < " + tcpIoLoopInfo.getWindow() +
                                " release the lock continue push data to device from remote, tcp header =" +
                                inputTcpHeader +
                                ", tcp loop = " + tcpIoLoopInfo);
                tcpIoLoopInfo.getExchangeSemaphore().release();
//                } else {
//                    Log.d(TcpIoLoop.class.getName(),
//                            "RECEIVE [ACK WITHOUT DATA(status=ESTABLISHED, size=0)], No data to remote ack to device, tcp header =" +
//                                    inputTcpHeader +
//                                    ", tcp loop = " + tcpIoLoop);
//                }
                return;
            }
            tcpIoLoopInfo.setCurrentRemoteToDeviceAck(inputTcpHeader.getSequenceNumber() + data.length);
            if (tcpIoLoopInfo.getRemoteChannel() == null || !tcpIoLoopInfo.getRemoteChannel().isOpen()) {
                Log.e(TcpIoLoopDeviceToRemoteTask.class.getName(),
                        "RECEIVE [ACK WITH DATA(status=ESTABLISHED, size=" + data.length +
                                ")], Fail to write data to remote because of remote channel has problem, tcp header =" +
                                inputTcpHeader +
                                ", tcp loop = " + tcpIoLoopInfo);
                tcpIoLoopInfo.setStatus(TcpIoLoopStatus.RESET);
                TcpIoLoopOutputWriter.INSTANCE.writeRstAckToQueue(tcpIoLoopInfo);
                return;
            }
            ByteBuf pshDataByteBuf = Unpooled.wrappedBuffer(data);
            Log.d(TcpIoLoopDeviceToRemoteTask.class.getName(),
                    "RECEIVE [ACK WITH DATA(status=ESTABLISHED, size=" + data.length +
                            ")], write data to remote, tcp header =" +
                            inputTcpHeader +
                            ", tcp loop = " + tcpIoLoopInfo + ", DATA: \n" + ByteBufUtil.prettyHexDump(pshDataByteBuf));
            tcpIoLoopInfo.getRemoteChannel().writeAndFlush(pshDataByteBuf);
            return;
        }
        if (TcpIoLoopStatus.FIN_WAITE1 == tcpIoLoopInfo.getStatus()) {
            Log.d(TcpIoLoopDeviceToRemoteTask.class.getName(),
                    "RECEIVE [ACK(status=FIN_WAITE1)], switch tcp loop status to FIN_WAITE2, tcp header ="
                            + inputTcpHeader + " tcp loop = " + tcpIoLoopInfo);
            tcpIoLoopInfo.setStatus(TcpIoLoopStatus.FIN_WAITE2);
            long latestMessageTime = tcpIoLoopInfo.getLatestMessageTime();
            Timer twoMslTimer = new Timer();
            twoMslTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    if (latestMessageTime == tcpIoLoopInfo.getLatestMessageTime()) {
                        Log.d(TcpIoLoopDeviceToRemoteTask.class.getName(),
                                "2MSL TIMER [ACK(status=FIN_WAITE1)], stop tcp loop as no message come, tcp header ="
                                        + inputTcpHeader + " tcp loop = " + tcpIoLoopInfo);
                        TcpIoLoopDeviceToRemoteTask.this.stop();
                    }
                }
            }, DEFAULT_2MSL_TIME);
            return;
        }
        if (TcpIoLoopStatus.LAST_ACK == tcpIoLoopInfo.getStatus()) {
            tcpIoLoopInfo.setStatus(TcpIoLoopStatus.CLOSED);
            Log.d(TcpIoLoopDeviceToRemoteTask.class.getName(),
                    "RECEIVE [ACK(status=LAST_ACK)], close tcp loop, tcp header ="
                            + inputTcpHeader + " tcp loop = " + tcpIoLoopInfo);
            this.stop();
            return;
        }
        Log.e(TcpIoLoopDeviceToRemoteTask.class.getName(),
                "RECEIVE [ACK], Tcp loop in illegal state, send RST back to device, tcp header ="
                        + inputTcpHeader + " tcp loop = " + tcpIoLoopInfo);
        tcpIoLoopInfo.setCurrentRemoteToDeviceAck(inputTcpHeader.getSequenceNumber());
        tcpIoLoopInfo.setCurrentRemoteToDeviceSeq(inputTcpHeader.getAcknowledgementNumber());
        tcpIoLoopInfo.setStatus(TcpIoLoopStatus.RESET);
        TcpIoLoopOutputWriter.INSTANCE.writeRstAckToQueue(tcpIoLoopInfo);
//        tcpIoLoop.destroy();
    }

    private void doRst(TcpIoLoopInfo tcpIoLoopInfo, TcpHeader inputTcpHeader) {
        Log.d(TcpIoLoopDeviceToRemoteTask.class.getName(),
                "RECEIVE [RST], destroy tcp loop, tcp header =" +
                        inputTcpHeader +
                        ", tcp loop = " + tcpIoLoopInfo);
        Timer twoMslTimer = new Timer();
        twoMslTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                Log.d(TcpIoLoopDeviceToRemoteTask.class.getName(),
                        "2MSL TIMER [RST], stop tcp loop as no message come, tcp header ="
                                + inputTcpHeader + " tcp loop = " + tcpIoLoopInfo);
                TcpIoLoopDeviceToRemoteTask.this.stop();
            }
        }, DEFAULT_2MSL_TIME);
    }

    private void doFin(TcpIoLoopInfo tcpIoLoopInfo, TcpHeader inputTcpHeader) {
        if (TcpIoLoopStatus.ESTABLISHED == tcpIoLoopInfo.getStatus()) {
            tcpIoLoopInfo.setStatus(TcpIoLoopStatus.CLOSE_WAIT);
            tcpIoLoopInfo.setCurrentRemoteToDeviceAck(inputTcpHeader.getSequenceNumber() + 1);
            tcpIoLoopInfo.setCurrentRemoteToDeviceSeq(inputTcpHeader.getAcknowledgementNumber());
            Log.d(TcpIoLoopDeviceToRemoteTask.class.getName(),
                    "RECEIVE [FIN(status=ESTABLISHED, STEP1)], switch tcp loop status to CLOSE_WAIT, tcp header =" +
                            inputTcpHeader +
                            ", tcp loop = " + tcpIoLoopInfo);
            TcpIoLoopOutputWriter.INSTANCE.writeAckToQueue(tcpIoLoopInfo, null);
            tcpIoLoopInfo.setStatus(TcpIoLoopStatus.LAST_ACK);
//            try {
//                tcpIoLoopInfo.getAckSemaphore().acquire();
//            } catch (InterruptedException e) {
//                Log.e(TcpIoLoop.class.getName(),
//                        "RECEIVE [FIN(status=ESTABLISHED, STEP1->STEP2 ERROR)], exception when switch status from CLOSE_WAIT to LAST_ACK, tcp header =" +
//                                inputTcpHeader +
//                                ", tcp loop = " + tcpIoLoopInfo, e);
//            }
            TcpIoLoopOutputWriter.INSTANCE.writeFinToQueue(tcpIoLoopInfo);
            Log.d(TcpIoLoopDeviceToRemoteTask.class.getName(),
                    "RECEIVE [FIN(status=ESTABLISHED, STEP2)], switch tcp loop status to LAST_ACK, tcp header =" +
                            inputTcpHeader +
                            ", tcp loop = " + tcpIoLoopInfo);
            return;
        }
        if (TcpIoLoopStatus.FIN_WAITE1 == tcpIoLoopInfo.getStatus()) {
//            tcpIoLoopInfo.getAckSemaphore().release();
            if (inputTcpHeader.isAck()) {
                Log.d(TcpIoLoopDeviceToRemoteTask.class.getName(),
                        "RECEIVE [FIN ACK(status=FIN_WAITE1)], close tcp loop, tcp header ="
                                + inputTcpHeader + " tcp loop = " + tcpIoLoopInfo);
                tcpIoLoopInfo.setStatus(TcpIoLoopStatus.TIME_WAITE);
                Timer twoMslTimer = new Timer();
                twoMslTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        Log.d(TcpIoLoopDeviceToRemoteTask.class.getName(),
                                "2MSL TIMER [FIN ACK(status=FIN_WAITE1)], stop tcp loop, tcp header ="
                                        + inputTcpHeader + " tcp loop = " + tcpIoLoopInfo);
                        TcpIoLoopDeviceToRemoteTask.this.stop();
                    }
                }, DEFAULT_2MSL_TIME);
                return;
            } else {
                Log.d(TcpIoLoopDeviceToRemoteTask.class.getName(),
                        "RECEIVE [FIN(status=FIN_WAITE1)], switch tcp loop status to FIN_WAITE2, send ack, tcp header ="
                                + inputTcpHeader + " tcp loop = " + tcpIoLoopInfo);
            }
            tcpIoLoopInfo.setStatus(TcpIoLoopStatus.FIN_WAITE2);
            return;
        }
        if (TcpIoLoopStatus.FIN_WAITE2 == tcpIoLoopInfo.getStatus()) {
            Log.d(TcpIoLoopDeviceToRemoteTask.class.getName(),
                    "RECEIVE [FIN(status=FIN_WAITE2)], switch tcp loop status to TIME_WAITE, send ack, tcp header ="
                            + inputTcpHeader + " tcp loop = " + tcpIoLoopInfo);
            long ackIn2MslTimer = inputTcpHeader.getSequenceNumber() + 1;
            long seqIn2MslTimer = inputTcpHeader.getAcknowledgementNumber();
            tcpIoLoopInfo.setStatus(TcpIoLoopStatus.TIME_WAITE);
            Timer twoMslTimer = new Timer();
            twoMslTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    tcpIoLoopInfo.setCurrentRemoteToDeviceAck(ackIn2MslTimer);
                    tcpIoLoopInfo.setCurrentRemoteToDeviceSeq(seqIn2MslTimer);
                    Log.d(TcpIoLoopDeviceToRemoteTask.class.getName(),
                            "2MSL TIMER [ACK(status=TIME_WAITE)], send ack and stop tcp loop, send ack, tcp header ="
                                    + inputTcpHeader + " tcp loop = " + tcpIoLoopInfo);
                    TcpIoLoopOutputWriter.INSTANCE.writeAckToQueue(tcpIoLoopInfo, null);
                    TcpIoLoopDeviceToRemoteTask.this.stop();
                }
            }, DEFAULT_2MSL_TIME);
            return;
        }
        Log.e(TcpIoLoopDeviceToRemoteTask.class.getName(),
                "RECEIVE [FIN] Tcp loop in illegal state, send RST back to device, tcp header ="
                        + inputTcpHeader + " tcp loop = " + tcpIoLoopInfo);
        tcpIoLoopInfo.setCurrentRemoteToDeviceAck(inputTcpHeader.getSequenceNumber());
        tcpIoLoopInfo.setCurrentRemoteToDeviceSeq(inputTcpHeader.getAcknowledgementNumber());
        tcpIoLoopInfo.setStatus(TcpIoLoopStatus.RESET);
        TcpIoLoopOutputWriter.INSTANCE.writeRstAckToQueue(tcpIoLoopInfo);
    }
}
