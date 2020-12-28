package com.ppaass.agent.android.io.process.tcp;

import com.ppaass.agent.android.io.protocol.ip.IpPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DuplexChannel;
import io.netty.util.ReferenceCountUtil;

@ChannelHandler.Sharable
public class TcpIoLoopRemoteToDeviceHandler extends ChannelDuplexHandler {
    public TcpIoLoopRemoteToDeviceHandler() {
    }

    @Override
    public void channelRead(ChannelHandlerContext remoteChannelContext, Object remoteMessage)
            throws Exception {
        DuplexChannel remoteChannel = (DuplexChannel) remoteChannelContext.channel();
        final TcpIoLoop tcpIoLoop = remoteChannel.attr(ITcpIoLoopConstant.TCP_LOOP).get();
        synchronized (tcpIoLoop) {
            ByteBuf remoteMessageByteBuf = (ByteBuf) remoteMessage;
            while (remoteMessageByteBuf.isReadable()) {
                int length = tcpIoLoop.getMss();
                if (remoteMessageByteBuf.readableBytes() < length) {
                    length = remoteMessageByteBuf.readableBytes();
                }
                byte[] ackData = ByteBufUtil.getBytes(remoteMessageByteBuf.readBytes(length));
                IpPacket ipPacketWroteToDevice =
                        TcpIoLoopRemoteToDeviceWriter.INSTANCE.buildPshAck(tcpIoLoop, ackData);
                tcpIoLoop.offerIpPacketToWindow(ipPacketWroteToDevice);
                tcpIoLoop.setRemoteSequence(tcpIoLoop.getRemoteSequence() + length);
            }
            ReferenceCountUtil.release(remoteMessage);
        }
    }
}
