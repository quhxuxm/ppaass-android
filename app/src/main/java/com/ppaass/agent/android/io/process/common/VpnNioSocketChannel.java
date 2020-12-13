package com.ppaass.agent.android.io.process.common;

import com.ppaass.agent.android.service.PpaassVpnService;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.nio.channels.SocketChannel;

public class VpnNioSocketChannel extends NioSocketChannel {
    private final PpaassVpnService ppaassVpnService;

    public VpnNioSocketChannel(PpaassVpnService ppaassVpnService) {
        this.ppaassVpnService = ppaassVpnService;
    }

    @Override
    protected SocketChannel javaChannel() {
        SocketChannel result = super.javaChannel();
        this.ppaassVpnService.protect(result.socket());
        return result;
    }
}
