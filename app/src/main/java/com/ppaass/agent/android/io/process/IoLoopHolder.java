package com.ppaass.agent.android.io.process;

import java.net.InetAddress;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

public class IoLoopHolder {
    public static final IoLoopHolder INSTANCE = new IoLoopHolder();
    private static final String LOOP_KEY_FORMAT = "%s:%s->%s:%s";
    private final ConcurrentMap<String, IoLoopWrapper> ioLoops;
    private final long TIME_OUT_INTERVAL = 1000 * 60 * 5;

    private static class IoLoopWrapper {
        private final IIoLoop loop;
        private long lastAccessTime;

        public IoLoopWrapper(IIoLoop loop, long lastAccessTime) {
            this.loop = loop;
            this.lastAccessTime = lastAccessTime;
        }
    }

    private IoLoopHolder() {
        this.ioLoops = new ConcurrentHashMap<>();
        Timer cleanTimer = new Timer("IO_LOOP_HOLDER_CLEANER", true);
        cleanTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                ioLoops.forEach((loopKey, ioLoopWrapper) -> {
                    if (System.currentTimeMillis() - ioLoopWrapper.lastAccessTime > TIME_OUT_INTERVAL) {
                        ioLoops.remove(loopKey);
                    }
                });
            }
        }, 0, 5000);
    }

    public String generateLoopKey(InetAddress sourceAddress, int sourcePort, InetAddress destinationAddress,
                                  int destinationPort) {
        return String.format(LOOP_KEY_FORMAT, sourceAddress.getHostAddress(), sourcePort,
                destinationAddress.getHostAddress(), destinationPort);
    }

    public IIoLoop computeIfAbsent(String loopKey,
                                   Function<String, ? extends IIoLoop> mappingFunction) {
        IoLoopWrapper wrapper = this.ioLoops.computeIfAbsent(loopKey, key -> {
            IIoLoop loop = mappingFunction.apply(key);
            return new IoLoopWrapper(loop, System.currentTimeMillis());
        });
        wrapper.lastAccessTime = System.currentTimeMillis();
        return wrapper.loop;
    }

    public IIoLoop getIoLoop(String loopKey) {
        IoLoopWrapper wrapper = ioLoops.get(loopKey);
        if (wrapper == null) {
            return null;
        }
        wrapper.lastAccessTime = System.currentTimeMillis();
        return wrapper.loop;
    }

    public void forEach(BiConsumer<String, ? super IIoLoop> handler) {
        ioLoops.forEach((loopKey, ioLoopWrapper) -> {
            handler.accept(loopKey, ioLoopWrapper.loop);
        });
    }

    public void remove(String ioLoopKey) {
        this.ioLoops.remove(ioLoopKey);
    }
}
