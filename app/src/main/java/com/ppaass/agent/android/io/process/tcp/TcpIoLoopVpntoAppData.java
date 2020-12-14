package com.ppaass.agent.android.io.process.tcp;

public class TcpIoLoopVpntoAppData {
    private TcpIoLoopVpnToAppCommand command;
    private byte[] data;

    public TcpIoLoopVpntoAppData() {
        this.data = new byte[]{};
    }

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
        if (data == null) {
            this.data = new byte[]{};
            return;
        }
        this.data = data;
    }

    @Override
    public String toString() {
        return "TcpIoLoopVpntoAppData{" +
                "command=" + command +
                ", data size=" + (data == null ? 0 : data.length) +
                '}';
    }
}
