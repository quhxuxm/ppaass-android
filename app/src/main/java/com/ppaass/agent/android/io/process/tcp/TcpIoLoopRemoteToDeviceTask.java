package com.ppaass.agent.android.io.process.tcp;

import android.util.Log;
import com.ppaass.agent.android.io.protocol.ip.IpPacket;

import java.io.OutputStream;

class TcpIoLoopRemoteToDeviceTask implements Runnable {
    private final TcpIoLoopInfo loopInfo;
    private final OutputStream remoteToDeviceStream;
    private boolean alive;

    public TcpIoLoopRemoteToDeviceTask(TcpIoLoopInfo loopInfo, OutputStream remoteToDeviceStream) {
        this.loopInfo = loopInfo;
        this.remoteToDeviceStream = remoteToDeviceStream;
        this.alive = true;
    }

    @Override
    public void run() {
        while (this.alive) {
            IpPacket outputPacket = this.loopInfo.pollRemoteToDeviceIpPacket();
            TcpIoLoopOutputWriter.INSTANCE
                    .writeIpPacketToDevice(outputPacket, this.loopInfo, this.remoteToDeviceStream);
        }
    }

    public synchronized void stop() {
        Log.d(TcpIoLoopRemoteToDeviceTask.class.getName(),
                "Stop tcp loop remote to device task, tcp loop = " + this.loopInfo);
        this.alive = false;
    }
}
