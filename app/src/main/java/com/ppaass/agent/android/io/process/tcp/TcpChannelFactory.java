package com.ppaass.agent.android.io.process.tcp;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.PooledObjectFactory;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;

public class TcpChannelFactory implements PooledObjectFactory<Channel> {
    private final Bootstrap proxyTcpChannelBootstrap;
    private GenericObjectPool<Channel> pool;

    public TcpChannelFactory(Bootstrap proxyTcpChannelBootstrap) {
        this.proxyTcpChannelBootstrap = proxyTcpChannelBootstrap;
    }

    public void attachPool(GenericObjectPool<Channel> pool) {
        this.pool = pool;
    }

    @Override
    public PooledObject<Channel> makeObject() throws Exception {
        ChannelFuture proxyTcpChannelConnectFuture = this.proxyTcpChannelBootstrap.connect().syncUninterruptibly();
        Channel proxyTcpChannel = proxyTcpChannelConnectFuture.channel();
        proxyTcpChannel.attr(ITcpIoLoopConstant.IProxyTcpChannelAttr.CHANNEL_POOL).set(this.pool);
        proxyTcpChannel.attr(ITcpIoLoopConstant.IProxyTcpChannelAttr.CLOSED_ALREADY).set(false);
        return new DefaultPooledObject<>(proxyTcpChannel);
    }

    @Override
    public void destroyObject(PooledObject<Channel> p) throws Exception {
        Channel proxyTcpChannel = p.getObject();
        Boolean closedAlready = proxyTcpChannel.attr(ITcpIoLoopConstant.IProxyTcpChannelAttr.CLOSED_ALREADY).get();
        if (!closedAlready) {
            proxyTcpChannel.flush();
            proxyTcpChannel.close().syncUninterruptibly();
        }
    }

    @Override
    public boolean validateObject(PooledObject<Channel> p) {
        Channel proxyTcpChannel = p.getObject();
        return proxyTcpChannel.isActive();
    }

    @Override
    public void activateObject(PooledObject<Channel> p) throws Exception {
    }

    @Override
    public void passivateObject(PooledObject<Channel> p) throws Exception {
        Channel proxyTcpChannel = p.getObject();
        proxyTcpChannel.flush();
        proxyTcpChannel.attr(ITcpIoLoopConstant.IProxyTcpChannelAttr.TCP_LOOP).set(null);
    }
}
