package com.ppaass.agent.android.io.process.tcp;

import com.ppaass.protocol.common.util.UUIDUtil;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.apache.commons.pool2.impl.GenericObjectPool;

import java.io.OutputStream;

public interface ITcpIoLoopConstant {
    interface IProxyTcpChannelAttr {
        AttributeKey<GenericObjectPool<Channel>> CHANNEL_POOL =
                AttributeKey.valueOf("CHANNEL_POOL");
        AttributeKey<Boolean> CLOSED_ALREADY =
                AttributeKey.valueOf("CLOSED_ALREADY");
        AttributeKey<OutputStream> REMOTE_TO_DEVICE_STREAM =
                AttributeKey.valueOf("REMOTE_TO_DEVICE_STREAM");
        AttributeKey<TcpIoLoop> TCP_LOOP =
                AttributeKey.valueOf("TCP_LOOP");
    }

    String TCP_IO_LOOP_KEY_FORMAT = "%s:%s->%s:%s";
    String AGENT_INSTANCE_ID = UUIDUtil.INSTANCE.generateUuid();
}
