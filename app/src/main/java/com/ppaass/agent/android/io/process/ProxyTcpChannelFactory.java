package com.ppaass.agent.android.io.process;

import com.ppaass.common.exception.PpaassException;
import com.ppaass.common.log.IPpaassLogger;
import com.ppaass.common.log.PpaassLoggerFactory;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.PooledObjectFactory;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;

import java.util.concurrent.ConcurrentHashMap;

public class ProxyTcpChannelFactory implements PooledObjectFactory<Channel> {
    private static final IPpaassLogger logger = PpaassLoggerFactory.INSTANCE.getLogger();
    private final Bootstrap proxyTcpChannelBootstrap;
    private GenericObjectPool<Channel> pool;

    public ProxyTcpChannelFactory(Bootstrap proxyTcpChannelBootstrap) {
        this.proxyTcpChannelBootstrap = proxyTcpChannelBootstrap;
    }

    public void attachPool(GenericObjectPool<Channel> pool) {
        this.pool = pool;
    }

    @Override
    public PooledObject<Channel> makeObject() throws Exception {
        logger.debug(() -> "Begin to create proxy channel object.");
        final ChannelFuture proxyChannelConnectFuture = this.proxyTcpChannelBootstrap
                .connect().sync();
        if (!proxyChannelConnectFuture.isSuccess()) {
            logger.error(() -> "Fail to create proxy channel because of exception.",
                    () -> new Object[]{proxyChannelConnectFuture.cause()});
            throw new PpaassException("Fail to create proxy channel because of exception.",
                    proxyChannelConnectFuture.cause());
        }
        Channel channel = proxyChannelConnectFuture.channel();
        channel.attr(ITcpIoLoopConstant.CHANNEL_POOL).set(this.pool);
        channel.attr(ITcpIoLoopConstant.CLOSED_ALREADY).set(false);
        channel.attr(ITcpIoLoopConstant.AGENT_CHANNELS).set(new ConcurrentHashMap<>());
        logger.debug(() -> "Success create proxy channel object, proxy channel = {}.",
                () -> new Object[]{channel.id().asLongText()});
        return new DefaultPooledObject<>(channel);
    }

    @Override
    public void destroyObject(PooledObject<Channel> pooledObject) throws Exception {
        Channel proxyChannel = pooledObject.getObject();
        logger.trace(() -> "Begin to destroy proxy channel object, proxy channel = {}.",
                () -> new Object[]{proxyChannel.id().asLongText()});
        Boolean closedAlready = proxyChannel.attr(ITcpIoLoopConstant.CLOSED_ALREADY).get();
        if (!closedAlready) {
            logger.debug(() -> "Channel still not close, invoke close on channel, proxy channel = {}.",
                    () -> new Object[]{proxyChannel.id().asLongText()});
            proxyChannel.flush();
            try {
                proxyChannel.close().syncUninterruptibly();
            } catch (Exception e) {
                logger.debug(() -> "Destroy proxy channel object have exception, proxy channel = {}.",
                        () -> new Object[]{proxyChannel.id().asLongText(), e});
            }
        }
        proxyChannel.attr(ITcpIoLoopConstant.AGENT_CHANNELS).set(null);
        proxyChannel.attr(ITcpIoLoopConstant.CLOSED_ALREADY).set(null);
        proxyChannel.attr(ITcpIoLoopConstant.CHANNEL_POOL).set(null);
        logger.debug(() -> "Success destroy proxy channel object, proxy channel = {}.",
                () -> new Object[]{proxyChannel.id().asLongText()});
    }

    @Override
    public boolean validateObject(PooledObject<Channel> pooledObject) {
        Channel proxyChannel = pooledObject.getObject();
        logger.trace(() -> "Begin to validate proxy channel object, proxy channel = {}.",
                () -> new Object[]{proxyChannel.id().asLongText()});
        boolean validStatus = proxyChannel.isActive();
        logger.trace(() -> "Proxy channel valid status = {}, proxy channel = {}.",
                () -> new Object[]{validStatus, proxyChannel.id().asLongText()});
        return validStatus;
    }

    @Override
    public void activateObject(PooledObject<Channel> pooledObject) throws Exception {
        Channel proxyChannel = pooledObject.getObject();
        logger.trace(() -> "Activate proxy channel object, proxy channel = {}.",
                () -> new Object[]{proxyChannel.id().asLongText()});
        if (!proxyChannel.isActive()) {
            logger.error(() -> "Proxy channel is not active, proxy channel = {}",
                    () -> new Object[]{proxyChannel.id().asLongText()});
            throw new PpaassException("Proxy channel is not active");
        }
    }

    @Override
    public void passivateObject(PooledObject<Channel> pooledObject) throws Exception {
        Channel proxyChannel = pooledObject.getObject();
        proxyChannel.flush();
        logger.debug(() -> "Passivate proxy channel object, proxy channel = {}.",
                () -> new Object[]{proxyChannel.id().asLongText()});
    }
}
