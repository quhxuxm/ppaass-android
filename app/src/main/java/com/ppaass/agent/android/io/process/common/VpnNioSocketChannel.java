package com.ppaass.agent.android.io.process.common;

import android.net.VpnService;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.nio.channels.SocketChannel;

public class VpnNioSocketChannel extends NioSocketChannel {
    private final VpnService vpnService;

    public VpnNioSocketChannel(VpnService vpnService) {
        this.vpnService = vpnService;
    }

    @Override
    protected SocketChannel javaChannel() {
        SocketChannel result = super.javaChannel();
        this.vpnService.protect(result.socket());
        return result;
    }
}
