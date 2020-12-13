package com.ppaass.agent.android.io.process;

import com.ppaass.agent.android.io.process.tcp.TcpIoLoop;
import com.ppaass.agent.android.io.process.udp.UdpIoLoop;
import io.netty.util.AttributeKey;

public interface IIoConstant {
    AttributeKey<TcpIoLoop> TCP_LOOP =
            AttributeKey.valueOf("TCP_LOOP");
    AttributeKey<UdpIoLoop> UDP_LOOP =
            AttributeKey.valueOf("UDP_LOOP");
    int TIMEOUT_SECONDS = 20;
}
