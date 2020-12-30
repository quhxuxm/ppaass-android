package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import com.ppaass.agent.android.io.protocol.ip.IpPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

@ChannelHandler.Sharable
public class TcpIoLoopRemoteToDeviceHandler extends ChannelInboundHandlerAdapter {
    public TcpIoLoopRemoteToDeviceHandler() {
    }

    @Override
    public void channelInactive(ChannelHandlerContext remoteChannelContext) throws Exception {
        Channel remoteChannel = remoteChannelContext.channel();
        final TcpIoLoop tcpIoLoop = remoteChannel.attr(ITcpIoLoopConstant.TCP_LOOP).get();
        if (tcpIoLoop.getStatus() != TcpIoLoopStatus.ESTABLISHED) {
            Log.d(TcpIoLoopRemoteToDeviceHandler.class.getName(),
                    "Remote channel closed, and tcp loop also NOT in ESTABLISHED, nothing to do, tcp loop =" +
                            tcpIoLoop);
            return;
        }
        tcpIoLoop.getExchangeSemaphore().acquire();
        Log.e(TcpIoLoopRemoteToDeviceHandler.class.getName(),
                "Remote connection closed, but tcp loop still in ESTABLISHED, close the tcp loop, tcp loop=" +
                        tcpIoLoop);
        IpPacket ipPacketWroteToDevice =
                TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildFin(
                        tcpIoLoop.getDestinationAddress(),
                        tcpIoLoop.getDestinationPort(),
                        tcpIoLoop.getSourceAddress(),
                        tcpIoLoop.getSourcePort(),
                        tcpIoLoop.getAccumulateRemoteToDeviceSequenceNumber(),
                        tcpIoLoop.getAccumulateRemoteToDeviceAcknowledgementNumber());
        TcpIoLoopRemoteToDeviceWriter.INSTANCE
                .writeIpPacketToDevice(ipPacketWroteToDevice, tcpIoLoop.getKey(),
                        tcpIoLoop.getRemoteToDeviceStream());
        tcpIoLoop.setStatus(TcpIoLoopStatus.FIN_WAITE1);
    }

    @Override
    public void channelRead(ChannelHandlerContext remoteChannelContext, Object remoteMessage)
            throws Exception {
        Channel remoteChannel = remoteChannelContext.channel();
        final TcpIoLoop tcpIoLoop = remoteChannel.attr(ITcpIoLoopConstant.TCP_LOOP).get();
        ByteBuf remoteMessageByteBuf = (ByteBuf) remoteMessage;
        while (remoteMessageByteBuf.isReadable()) {
            tcpIoLoop.getExchangeSemaphore().acquire();
            int length = tcpIoLoop.getMss();
            if (remoteMessageByteBuf.readableBytes() < length) {
                length = remoteMessageByteBuf.readableBytes();
            }
            byte[] ackData = ByteBufUtil.getBytes(remoteMessageByteBuf.readBytes(length));
            IpPacket ipPacketWroteToDevice =
                    TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildPshAck(
                            tcpIoLoop.getDestinationAddress(),
                            tcpIoLoop.getDestinationPort(),
                            tcpIoLoop.getSourceAddress(),
                            tcpIoLoop.getSourcePort(),
                            tcpIoLoop.getAccumulateRemoteToDeviceSequenceNumber(),
                            tcpIoLoop.getAccumulateRemoteToDeviceAcknowledgementNumber()
                            , ackData);
            tcpIoLoop.getWindow().offer(ipPacketWroteToDevice);
            TcpIoLoopRemoteToDeviceWriter.INSTANCE
                    .writeIpPacketToDevice(ipPacketWroteToDevice, tcpIoLoop.getKey(),
                            tcpIoLoop.getRemoteToDeviceStream());
            //Update sequence number after the data sent to device.
            tcpIoLoop.increaseAccumulateRemoteToDeviceSequenceNumber(length);
        }
        ReferenceCountUtil.release(remoteMessage);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext remoteChannelContext, Throwable cause) throws Exception {
        Channel remoteChannel = remoteChannelContext.channel();
        final TcpIoLoop tcpIoLoop = remoteChannel.attr(ITcpIoLoopConstant.TCP_LOOP).get();
        Log.e(TcpIoLoopRemoteToDeviceHandler.class.getName(),
                "Exception for tcp loop remote channel, tcp loop=" + tcpIoLoop, cause);
        if (cause.getMessage() != null && cause.getMessage().contains("Connection reset by peer")) {
            Log.e(TcpIoLoopRemoteToDeviceHandler.class.getName(),
                    "Connection reset by peer exception happen, reset the tcp loop, tcp loop=" + tcpIoLoop);
            IpPacket ipPacketWroteToDevice =
                    TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildRstAck(
                            tcpIoLoop.getDestinationAddress(),
                            tcpIoLoop.getDestinationPort(),
                            tcpIoLoop.getSourceAddress(),
                            tcpIoLoop.getSourcePort(),
                            tcpIoLoop.getAccumulateRemoteToDeviceSequenceNumber(),
                            tcpIoLoop.getAccumulateRemoteToDeviceAcknowledgementNumber());
            TcpIoLoopRemoteToDeviceWriter.INSTANCE
                    .writeIpPacketToDevice(ipPacketWroteToDevice, tcpIoLoop.getKey(),
                            tcpIoLoop.getRemoteToDeviceStream());
            tcpIoLoop.reset();
        }
    }
}
