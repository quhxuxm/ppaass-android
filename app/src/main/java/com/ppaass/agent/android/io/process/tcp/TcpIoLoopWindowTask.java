package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import com.ppaass.agent.android.io.protocol.ip.IpPacket;

class TcpIoLoopWindowTask implements Runnable {
    private final TcpIoLoop loop;
    private boolean alive;
    private boolean pause;
    private final Object waitToReadMore;

    public TcpIoLoopWindowTask(TcpIoLoop loop) {
        this.loop = loop;
        this.alive = true;
        this.pause = false;
        this.waitToReadMore = new Object();
    }

    public synchronized void stop() {
        Log.d(TcpIoLoopWindowTask.class.getName(),
                "Stop tcp loop remote to device task, tcp loop = " + this.loop);
        this.alive = false;
        synchronized (this.waitToReadMore) {
            this.waitToReadMore.notifyAll();
        }
        synchronized (this) {
            this.notifyAll();
        }
    }

    public synchronized void pause() {
        this.pause = true;
    }

    public synchronized void resume() {
        this.pause = false;
        this.notifyAll();
    }

    public void resumeToReadMore() {
        synchronized (this.waitToReadMore) {
            this.waitToReadMore.notifyAll();
        }
    }

    @Override
    public void run() {
        while (this.alive) {
            synchronized (this) {
                if (this.pause) {
                    try {
                        Log.d(TcpIoLoopWindowTask.class.getName(), "Writing thread PAUSED FOR ACK, tcp loop = " + this.loop);
                        this.wait();
                        Log.d(TcpIoLoopWindowTask.class.getName(), "Writing thread RESUMED FOR ACK, tcp loop = " + this.loop);
                    } catch (InterruptedException e) {
                        Log.e(TcpIoLoopWindowTask.class.getName(),
                                "Fail to pause tcp loop writing thread because of exception, tcp loop = " + this.loop,
                                e);
                        continue;
                    }
                }
            }
            IpPacket ipPacketInWindow = this.loop.pollIpPacketFromWindow();
            if (ipPacketInWindow == null) {
                synchronized (this.waitToReadMore) {
                    try {
                        Log.d(TcpIoLoopWindowTask.class.getName(),
                                "Writing thread WAITING to read more data, tcp loop = " + this.loop);
                        this.waitToReadMore.wait();
                        Log.d(TcpIoLoopWindowTask.class.getName(),
                                "Writing thread CONTINUE read more data, tcp loop = " + this.loop);
                    } catch (InterruptedException e) {
                        Log.e(TcpIoLoopWindowTask.class.getName(),
                                "Fail to waite read more because of exception, tcp lool = " + this.loop,
                                e);
                    }
                }
                continue;
            }
            TcpIoLoopRemoteToDeviceWriter.INSTANCE
                    .writeIpPacketToDevice(ipPacketInWindow, this.loop.getKey(), this.loop.getRemoteToDeviceStream());
            this.pause();
        }
    }
}
