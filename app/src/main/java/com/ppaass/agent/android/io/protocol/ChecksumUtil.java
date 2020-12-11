package com.ppaass.agent.android.io.protocol;

import java.nio.ByteBuffer;

public class ChecksumUtil {
    public static final ChecksumUtil INSTANCE = new ChecksumUtil();

    private ChecksumUtil() {
    }

    public int checksum(byte[] bytesToDoChecksum) {
        int checksum = 0;
        ByteBuffer byteBuffer = ByteBuffer.wrap(bytesToDoChecksum);
        while (byteBuffer.remaining() > 1) {
            int currentShort = byteBuffer.getShort() & 0xFFFF;
            checksum += currentShort;
            while (checksum >> 16 > 0) {
                int tmp = checksum & 0xFFFF;
                checksum = (tmp + (checksum >> 16));
            }
        }
        if (byteBuffer.remaining() == 1) {
            byte finalByte = byteBuffer.get();
            int finalShort = finalByte << 8;
            checksum += finalShort;
            while (checksum >> 16 > 0) {
                int tmp = checksum & 0xFFFF;
                checksum = (tmp + (checksum >> 16));
            }
        }
        checksum = ~checksum;
        return checksum;
    }
}
