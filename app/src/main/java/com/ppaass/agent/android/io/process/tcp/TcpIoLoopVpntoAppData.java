package com.ppaass.agent.android.io.process.tcp;

import java.util.Arrays;

public class TcpIoLoopVpntoAppData {
    private TcpIoLoopVpnToAppCommand command;
    private byte[] data;

    public TcpIoLoopVpnToAppCommand getCommand() {
        return command;
    }

    public void setCommand(TcpIoLoopVpnToAppCommand command) {
        this.command = command;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    @Override
    public String toString() {
        return "TcpIoLoopVpntoAppData{" +
                "command=" + command +
                ", data=" + Arrays.toString(data) +
                '}';
    }
}
