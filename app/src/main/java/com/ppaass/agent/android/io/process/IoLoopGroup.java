package com.ppaass.agent.android.io.process;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class IoLoopGroup {
    private final ExecutorService ioLoopExecutorGroup;

    public IoLoopGroup() {
        this.ioLoopExecutorGroup = Executors.newFixedThreadPool(10);
    }

    public void submit(IIoLoop ioLoop) {
        this.ioLoopExecutorGroup.submit(ioLoop);
    }
}
