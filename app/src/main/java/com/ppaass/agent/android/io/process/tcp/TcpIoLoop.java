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

import java.io.OutputStream;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingDeque;

import static com.ppaass.agent.android.io.process.tcp.ITcpIoLoopConstant.TCP_LOOP;

public class TcpIoLoop implements Runnable {
    private final TcpIoLoopInfo loopInfo;
    private final BlockingDeque<IpPacket> deviceToRemoteIpPacketQueue;
    private final Bootstrap remoteBootstrap;
    private final OutputStream remoteToDeviceStream;
    private final ConcurrentMap<String, TcpIoLoop> tcpIoLoopsContainer;
    private boolean alive;

    public TcpIoLoop(TcpIoLoopInfo loopInfo, Bootstrap remoteBootstrap, OutputStream remoteToDeviceStream,
                     ConcurrentMap<String, TcpIoLoop> tcpIoLoopsContainer) {
        this.loopInfo = loopInfo;
        this.remoteBootstrap = remoteBootstrap;
        this.remoteToDeviceStream = remoteToDeviceStream;
        this.tcpIoLoopsContainer = tcpIoLoopsContainer;
        deviceToRemoteIpPacketQueue = new LinkedBlockingDeque<>();
        this.alive = true;
    }

    public void offerIpPacket(IpPacket ipPacket) {
        try {
            this.deviceToRemoteIpPacketQueue.put(ipPacket);
        } catch (InterruptedException e) {
            Log.e(TcpIoLoop.class.getName(),
                    "Fail to put ip packet into the device to remote queue because of exception, tcp loop = " +
                            this.loopInfo, e);
        }
    }

    public IpPacket pollIpPacket() {
        try {
            return this.deviceToRemoteIpPacketQueue.take();
        } catch (InterruptedException e) {
            Log.e(TcpIoLoop.class.getName(),
                    "Fail to take ip packet from the device to remote queue because of exception, tcp loop = " +
                            this.loopInfo, e);
            return null;
        }
    }

    public TcpIoLoopInfo getLoopInfo() {
        return loopInfo;
    }

    public void stop() {
        Log.d(TcpIoLoop.class.getName(), "Stop tcp loop, tcp loop = " + this.loopInfo);
        this.alive = false;
        this.deviceToRemoteIpPacketQueue.clear();
        this.loopInfo.setStatus(TcpIoLoopStatus.CLOSED);
        this.loopInfo.setCurrentRemoteToDeviceAck(-1);
        this.loopInfo.setCurrentRemoteToDeviceSeq(-1);
        this.loopInfo.getAckSemaphore().release();
        if (this.loopInfo.getRemoteChannel() != null) {
            if (this.loopInfo.getRemoteChannel().isOpen()) {
                this.loopInfo.getRemoteChannel().close();
            }
        }
        this.tcpIoLoopsContainer.remove(this.loopInfo.getKey());
    }

    @Override
    public void run() {
        while (this.alive) {
            IpPacket inputIpPacket = this.pollIpPacket();
            if (inputIpPacket == null) {
                continue;
            }
            TcpPacket inputTcpPacket = (TcpPacket) inputIpPacket.getData();
            TcpHeader inputTcpHeader = inputTcpPacket.getHeader();
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
            Log.e(TcpIoLoop.class.getName(),
                    "Tcp loop is NOT in LISTEN status, return RST back to device, tcp header = " + inputTcpHeader +
                            ", tcp loop = " + tcpIoLoopInfo);
            tcpIoLoopInfo.setCurrentRemoteToDeviceAck(inputTcpHeader.getSequenceNumber());
            tcpIoLoopInfo.setCurrentRemoteToDeviceSeq(inputTcpHeader.getAcknowledgementNumber());
            TcpIoLoopOutputWriter.INSTANCE.writeRstAck(tcpIoLoopInfo, this.remoteToDeviceStream);
            return;
        }
        Log.v(TcpIoLoop.class.getName(),
                "RECEIVE [SYN], initializing connection, tcp header = " + inputTcpHeader +
                        ", tcp loop = " + tcpIoLoopInfo);
        this.remoteBootstrap
                .connect(tcpIoLoopInfo.getDestinationAddress(), tcpIoLoopInfo.getDestinationPort()).addListener(
                (ChannelFutureListener) connectResultFuture -> {
                    if (!connectResultFuture.isSuccess()) {
                        Log.e(TcpIoLoop.class.getName(),
                                "RECEIVE [SYN], initialize connection FAIL send RST back to device, tcp header ="
                                        + inputTcpHeader + " tcp loop = " + tcpIoLoopInfo);
                        tcpIoLoopInfo.setCurrentRemoteToDeviceAck(inputTcpHeader.getSequenceNumber());
                        tcpIoLoopInfo.setCurrentRemoteToDeviceSeq(inputTcpHeader.getAcknowledgementNumber());
                        TcpIoLoopOutputWriter.INSTANCE.writeRstAck(tcpIoLoopInfo, this.remoteToDeviceStream);
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
                    Log.d(TcpIoLoop.class.getName(),
                            "RECEIVE [SYN], initializing connection success, switch tcp loop to SYN_RECIVED, tcp header = " +
                                    inputTcpHeader +
                                    ", tcp loop = " + tcpIoLoopInfo);
                    TcpIoLoopOutputWriter.INSTANCE.writeSynAck(tcpIoLoopInfo, remoteToDeviceStream);
                });
    }

    private void doPsh(TcpIoLoopInfo tcpIoLoopInfo, TcpHeader inputTcpHeader, byte[] data) {
        //Psh ack
        tcpIoLoopInfo.setCurrentRemoteToDeviceSeq(inputTcpHeader.getAcknowledgementNumber());
        if (data == null) {
            tcpIoLoopInfo.setCurrentRemoteToDeviceAck(inputTcpHeader.getSequenceNumber());
            Log.d(TcpIoLoop.class.getName(),
                    "RECEIVE [PSH ACK WITHOUT DATA(size=0)], No data to remote ack to device, tcp header =" +
                            inputTcpHeader +
                            ", tcp loop = " + tcpIoLoopInfo);
//                    TcpIoLoopOutputWriter.INSTANCE.writeAck(tcpIoLoop, null, this.remoteToDeviceStream);
            tcpIoLoopInfo.getAckSemaphore().release();
            return;
        }
        tcpIoLoopInfo.setCurrentRemoteToDeviceAck(inputTcpHeader.getSequenceNumber() + data.length);
        ByteBuf pshDataByteBuf = Unpooled.wrappedBuffer(data);
        Log.d(TcpIoLoop.class.getName(),
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
            Log.d(TcpIoLoop.class.getName(),
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
                Log.d(TcpIoLoop.class.getName(),
                        "RECEIVE [ACK WITHOUT DATA(status=ESTABLISHED, size=0, win_diff = " +
                                (tcpIoLoopInfo.getCurrentRemoteToDeviceAck() - inputTcpHeader.getSequenceNumber()) +
                                ")], No data to remote ack to device, win_diff < " + tcpIoLoopInfo.getWindow() +
                                " release the lock continue push data to device from remote, tcp header =" +
                                inputTcpHeader +
                                ", tcp loop = " + tcpIoLoopInfo);
                tcpIoLoopInfo.getAckSemaphore().release();
//                } else {
//                    Log.d(TcpIoLoop.class.getName(),
//                            "RECEIVE [ACK WITHOUT DATA(status=ESTABLISHED, size=0)], No data to remote ack to device, tcp header =" +
//                                    inputTcpHeader +
//                                    ", tcp loop = " + tcpIoLoop);
//                }
                return;
            }
            tcpIoLoopInfo.setCurrentRemoteToDeviceAck(inputTcpHeader.getSequenceNumber() + data.length);
            ByteBuf pshDataByteBuf = Unpooled.wrappedBuffer(data);
            Log.d(TcpIoLoop.class.getName(),
                    "RECEIVE [ACK WITH DATA(status=ESTABLISHED, size=" + data.length +
                            ")], write data to remote, tcp header =" +
                            inputTcpHeader +
                            ", tcp loop = " + tcpIoLoopInfo + ", DATA: \n" + ByteBufUtil.prettyHexDump(pshDataByteBuf));
            tcpIoLoopInfo.getRemoteChannel().writeAndFlush(pshDataByteBuf);
            return;
        }
        if (TcpIoLoopStatus.FIN_WAITE1 == tcpIoLoopInfo.getStatus()) {
            Log.d(TcpIoLoop.class.getName(),
                    "RECEIVE [ACK(status=FIN_WAITE1)], switch tcp loop status to FIN_WAITE2, tcp header ="
                            + inputTcpHeader + " tcp loop = " + tcpIoLoopInfo);
            tcpIoLoopInfo.setStatus(TcpIoLoopStatus.FIN_WAITE2);
            return;
        }
        if (TcpIoLoopStatus.FIN_WAITE2 == tcpIoLoopInfo.getStatus()) {
            long ackIn2MslTimer = inputTcpHeader.getSequenceNumber() + 1;
            long seqIn2MslTimer = inputTcpHeader.getAcknowledgementNumber();
            tcpIoLoopInfo.setStatus(TcpIoLoopStatus.TIME_WAITE);
            Log.d(TcpIoLoop.class.getName(),
                    "RECEIVE [ACK(status=FIN_WAITE2)], switch tcp loop status to TIME_WAITE, send ack, tcp header ="
                            + inputTcpHeader + " tcp loop = " + tcpIoLoopInfo);
            Timer twoMslTimer = new Timer();
            twoMslTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    tcpIoLoopInfo.setCurrentRemoteToDeviceAck(ackIn2MslTimer);
                    tcpIoLoopInfo.setCurrentRemoteToDeviceSeq(seqIn2MslTimer);
                    Log.d(TcpIoLoop.class.getName(),
                            "SEND [ACK(status=TIME_WAITE)], destroy connection in 2MSL, send ack, tcp header ="
                                    + inputTcpHeader + " tcp loop = " + tcpIoLoopInfo);
                    TcpIoLoopOutputWriter.INSTANCE.writeAck(tcpIoLoopInfo, null, remoteToDeviceStream);
                    TcpIoLoop.this.stop();
                }
            }, 1000 * 120);
            return;
        }
        if (TcpIoLoopStatus.LAST_ACK == tcpIoLoopInfo.getStatus()) {
            tcpIoLoopInfo.setStatus(TcpIoLoopStatus.CLOSED);
            Log.d(TcpIoLoop.class.getName(),
                    "RECEIVE [ACK(status=LAST_ACK)], close tcp loop, tcp header ="
                            + inputTcpHeader + " tcp loop = " + tcpIoLoopInfo);
            this.stop();
            return;
        }
        Log.e(TcpIoLoop.class.getName(),
                "Tcp loop in illegal state, send RST back to device, tcp header ="
                        + inputTcpHeader + " tcp loop = " + tcpIoLoopInfo);
        tcpIoLoopInfo.setCurrentRemoteToDeviceAck(inputTcpHeader.getSequenceNumber());
        tcpIoLoopInfo.setCurrentRemoteToDeviceSeq(inputTcpHeader.getAcknowledgementNumber());
        TcpIoLoopOutputWriter.INSTANCE.writeRstAck(tcpIoLoopInfo, remoteToDeviceStream);
//        tcpIoLoop.destroy();
    }

    private void doRst(TcpIoLoopInfo tcpIoLoopInfo, TcpHeader inputTcpHeader) {
        Log.d(TcpIoLoop.class.getName(),
                "RECEIVE [RST], destroy tcp loop, tcp header =" +
                        inputTcpHeader +
                        ", tcp loop = " + tcpIoLoopInfo);
        this.stop();
    }

    private void doFin(TcpIoLoopInfo tcpIoLoopInfo, TcpHeader inputTcpHeader) {
        tcpIoLoopInfo.setStatus(TcpIoLoopStatus.CLOSE_WAIT);
        tcpIoLoopInfo.setCurrentRemoteToDeviceAck(inputTcpHeader.getSequenceNumber() + 1);
        Log.d(TcpIoLoop.class.getName(),
                "RECEIVE [FIN], switch tcp loop status to CLOSE_WAIT, tcp header =" +
                        inputTcpHeader +
                        ", tcp loop = " + tcpIoLoopInfo);
        TcpIoLoopOutputWriter.INSTANCE.writeAck(tcpIoLoopInfo, null, this.remoteToDeviceStream);
        tcpIoLoopInfo.setStatus(TcpIoLoopStatus.LAST_ACK);
        Log.d(TcpIoLoop.class.getName(),
                "RECEIVE [FIN], switch tcp loop status to LAST_ACK, tcp header =" +
                        inputTcpHeader +
                        ", tcp loop = " + tcpIoLoopInfo);
        TcpIoLoopOutputWriter.INSTANCE.writeFin(tcpIoLoopInfo, this.remoteToDeviceStream);
    }
}
