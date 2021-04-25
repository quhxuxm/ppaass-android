package com.ppaass.agent.android.io.process;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.apache.commons.pool2.impl.GenericObjectPool;

import java.util.concurrent.ConcurrentMap;

public interface ITcpIoLoopConstant {
    String IO_LOOP_KEY_FORMAT = "%s://%s:%s->%s:%s";
    AttributeKey<ConcurrentMap<String, Channel>> AGENT_CHANNELS =
            AttributeKey.valueOf("AGENT_CHANNELS");
    AttributeKey<GenericObjectPool<Channel>> CHANNEL_POOL =
            AttributeKey.valueOf("CHANNEL_POOL");
    AttributeKey<Boolean> CLOSED_ALREADY =
            AttributeKey.valueOf("CLOSED_ALREADY");
}
