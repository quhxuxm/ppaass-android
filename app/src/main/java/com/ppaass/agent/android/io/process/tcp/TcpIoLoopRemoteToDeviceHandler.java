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

import static com.ppaass.agent.android.io.process.tcp.ITcpIoLoopConstant.*;

@ChannelHandler.Sharable
public class TcpIoLoopRemoteToDeviceHandler extends ChannelInboundHandlerAdapter {
    public TcpIoLoopRemoteToDeviceHandler() {
    }

    @Override
    public void channelInactive(ChannelHandlerContext remoteChannelContext) throws Exception {
        Channel remoteChannel = remoteChannelContext.channel();
        final TcpIoLoop tcpIoLoop = remoteChannel.attr(ITcpIoLoopConstant.TCP_LOOP).get();
        if (tcpIoLoop.getStatus() == TcpIoLoopStatus.CLOSED) {
            return;
        }
        Log.e(TcpIoLoopRemoteToDeviceHandler.class.getName(),
                "Remote connection closed, reset the tcp loop, tcp loop=" + tcpIoLoop);
        Long deviceInputSequenceNumber = remoteChannel.attr(DEVICE_INPUT_SEQUENCE_NUMBER).get();
        Long deviceInputAckNumber = remoteChannel.attr(DEVICE_INPUT_ACKNOWLEDGEMENT_NUMBER).get();
        if (deviceInputAckNumber != null && deviceInputSequenceNumber != null) {
            IpPacket ipPacketWroteToDevice =
                    TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildRstAck(
                            tcpIoLoop.getDestinationAddress(),
                            tcpIoLoop.getDestinationPort(),
                            tcpIoLoop.getSourceAddress(),
                            tcpIoLoop.getSourcePort(),
                            deviceInputAckNumber,
                            deviceInputSequenceNumber);
            TcpIoLoopRemoteToDeviceWriter.INSTANCE
                    .writeIpPacketToDevice(ipPacketWroteToDevice, tcpIoLoop.getKey(),
                            tcpIoLoop.getRemoteToDeviceStream());
        }
        tcpIoLoop.destroy();
    }

    @Override
    public void channelRead(ChannelHandlerContext remoteChannelContext, Object remoteMessage)
            throws Exception {
        Channel remoteChannel = remoteChannelContext.channel();
        final TcpIoLoop tcpIoLoop = remoteChannel.attr(ITcpIoLoopConstant.TCP_LOOP).get();
        ByteBuf remoteMessageByteBuf = (ByteBuf) remoteMessage;
//        int readableBytesNumber = remoteMessageByteBuf.readableBytes();
        Long deviceInputSequenceNumber = remoteChannel.attr(DEVICE_INPUT_SEQUENCE_NUMBER).get();
        Long deviceInputAckNumber = remoteChannel.attr(DEVICE_INPUT_ACKNOWLEDGEMENT_NUMBER).get();
        Integer deviceInputDataLength = remoteChannel.attr(DEVICE_INPUT_DATA_LENGTH).get();
        while (remoteMessageByteBuf.isReadable()) {
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
                            deviceInputAckNumber,
                            deviceInputSequenceNumber + deviceInputDataLength
                            , ackData);
            deviceInputAckNumber += length;
            remoteChannel.attr(DEVICE_INPUT_ACKNOWLEDGEMENT_NUMBER).set(deviceInputAckNumber);
            TcpIoLoopRemoteToDeviceWriter.INSTANCE
                    .writeIpPacketToDevice(ipPacketWroteToDevice, tcpIoLoop.getKey(),
                            tcpIoLoop.getRemoteToDeviceStream());
        }
//        if (readableBytesNumber < remoteMessageByteBuf.capacity()) {
//            IpPacket finPacket =
//                    TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildFin(
//                            tcpIoLoop.getDestinationAddress(),
//                            tcpIoLoop.getDestinationPort(),
//                            tcpIoLoop.getSourceAddress(),
//                            tcpIoLoop.getSourcePort(),
//                            deviceInputAckNumber + 1,
//                            deviceInputSequenceNumber + deviceInputDataLength);
//            tcpIoLoop.setStatus(TcpIoLoopStatus.FIN_WAITE1);
//            TcpIoLoopRemoteToDeviceWriter.INSTANCE
//                    .writeIpPacketToDevice(finPacket, tcpIoLoop.getKey(),
//                            tcpIoLoop.getRemoteToDeviceStream());
//        }
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
            Long deviceInputSequenceNumber = remoteChannel.attr(DEVICE_INPUT_SEQUENCE_NUMBER).get();
            Long deviceInputAckNumber = remoteChannel.attr(DEVICE_INPUT_ACKNOWLEDGEMENT_NUMBER).get();
            IpPacket ipPacketWroteToDevice =
                    TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildRstAck(
                            tcpIoLoop.getDestinationAddress(),
                            tcpIoLoop.getDestinationPort(),
                            tcpIoLoop.getSourceAddress(),
                            tcpIoLoop.getSourcePort(),
                            deviceInputAckNumber,
                            deviceInputSequenceNumber);
            TcpIoLoopRemoteToDeviceWriter.INSTANCE
                    .writeIpPacketToDevice(ipPacketWroteToDevice, tcpIoLoop.getKey(),
                            tcpIoLoop.getRemoteToDeviceStream());
            tcpIoLoop.destroy();
        }
    }
}
