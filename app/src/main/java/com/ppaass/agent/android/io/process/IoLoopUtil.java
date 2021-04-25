package com.ppaass.agent.android.io.process;

import com.ppaass.protocol.base.ip.IpDataProtocol;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static com.ppaass.agent.android.io.process.ITcpIoLoopConstant.IO_LOOP_KEY_FORMAT;

public class IoLoopUtil {
    public static IoLoopUtil INSTANCE = new IoLoopUtil();

    private IoLoopUtil() {
    }

    public String generateIoLoopKey(IpDataProtocol protocol, String sourceAddress, int sourcePort,
                                    String destinationAddress,
                                    int destinationPort) {
        return String.format(IO_LOOP_KEY_FORMAT, protocol, sourceAddress,
                sourcePort,
                destinationAddress, destinationPort);
    }

    public String generateIoLoopKey(IpDataProtocol protocol, byte[] sourceAddressInBytes, int sourcePort,
                                    byte[] destinationAddressInBytes,
                                    int destinationPort) {
        try {
            return String.format(IO_LOOP_KEY_FORMAT, protocol,
                    InetAddress.getByAddress(sourceAddressInBytes).getHostAddress(),
                    sourcePort,
                    InetAddress.getByAddress(destinationAddressInBytes).getHostAddress(), destinationPort);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
