package com.ppaass.agent.android.io.process.tcp;

public enum TcpIoLoopStatus {
    LISTEN,
    SYN_RECEIVED,
    ESTABLISHED,
    CLOSE_WAIT,
    LAST_ACK,
    CLOSED
}
