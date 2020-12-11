package com.ppaass.agent.android.io.process;

import com.ppaass.agent.android.io.protocol.ip.IpPacket;

public interface IIoLoop<P> {
    String getKey();

    void loop();

    void push(IpPacket ipPacket);
}
