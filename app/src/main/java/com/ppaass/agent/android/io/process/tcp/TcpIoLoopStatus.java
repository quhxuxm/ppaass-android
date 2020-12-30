package com.ppaass.agent.android.io.process.tcp;

public enum TcpIoLoopStatus {
    LISTEN,
    SYN_RECEIVED,
    SYN_SENT,
    ESTABLISHED,
    CLOSE_WAIT,
    CLOSING,
    LAST_ACK,
    CLOSED,
    FIN_WAITE1,
    FIN_WAITE2,
    TIME_WAITE
}
