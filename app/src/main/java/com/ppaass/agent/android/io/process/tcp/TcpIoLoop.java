package com.ppaass.agent.android.io.process.tcp;

import com.ppaass.agent.android.io.process.IIoLoop;
import com.ppaass.agent.android.io.protocol.ip.IpPacket;

public class TcpIoLoop implements IIoLoop {
    @Override
    public String getKey() {
        return null;
    }

    @Override
    public void loop() {
    }

    @Override
    public void push(IpPacket ipPacket) {
    }
}
