package com.ppaass.agent.android.io.process.tcp;

import io.netty.util.AttributeKey;

import java.io.OutputStream;

public interface ITcpIoLoopConstant {
    AttributeKey<TcpIoLoop> TCP_LOOP =
            AttributeKey.valueOf("TCP_LOOP");
    AttributeKey<OutputStream> REMOTE_TO_DEVICE_STREAM =
            AttributeKey.valueOf("REMOTE_TO_DEVICE_STREAM");
    String TCP_IO_LOOP_KEY_FORMAT = "%s:%s->%s:%s";
}
