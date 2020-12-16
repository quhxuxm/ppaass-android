package com.ppaass.agent.android;

import com.ppaass.agent.android.io.protocol.ip.IpPacket;
import com.ppaass.agent.android.io.protocol.ip.IpPacketReader;
import com.ppaass.agent.android.io.protocol.ip.IpPacketWriter;
import org.junit.Test;

import java.util.Arrays;

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
public class ProtocolTest {
    @Test
    public void testTcp() {
        short[] ipData = new short[] {
                0x45,0x00,0x00,0x34,0xc1,0xd1,
                0x40,0x00,0x80,0x06,0x0e,0xe0,
                0x0a,0xaf,0x04,0xdc,0x0a,0xdc,0x0f,
                0xac,0xfc,0x34,0x1f,0x90,0x94,0x6b,
                0x2d,0x5f,0x00,0x00,0x00,0x00,0x80,
                0x02,0xfa,0xf0,0x6c,0x7d,0x00,0x00,
                0x02,0x04,0x05,0xb4,0x01,0x03,0x03,
                0x08,0x01,0x01,0x04,0x02
        };
        byte[] ipDataByteArray = new byte[ipData.length];
        for (int i = 0; i < ipData.length; i++) {
            ipDataByteArray[i] = (byte) ipData[i];
        }
        IpPacket ipPacket = IpPacketReader.INSTANCE.parse(ipDataByteArray);
        System.out.println(ipPacket);
        System.out.println("======================================");
        byte[] ipPacketArray = IpPacketWriter.INSTANCE.write(ipPacket);
        short[] ipPacketArrayInShort = new short[ipPacketArray.length];
        for (int i = 0; i < ipPacketArray.length; i++) {
            ipPacketArrayInShort[i] = (short) (ipPacketArray[i] & 0xFF);
        }
        String[] hexArray = new String[ipPacketArrayInShort.length];
        for (int i = 0; i < ipPacketArrayInShort.length; i++) {
            hexArray[i] = Integer.toHexString(ipPacketArrayInShort[i]);
        }
        System.out.println(Arrays.toString(hexArray));
    }

    @Test
    public void testUdp() {
        short[] ipData = new short[]{
                0x45, 0x00
                , 0x00, 0x69, 0x54, 0xf5, 0x00, 0x00, 0x01, 0x11, 0x69, 0xf1, 0x0a, 0xaf, 0x04, 0xe1, 0xe4, 0x07
                , 0x07, 0x07, 0x4e, 0x27, 0x4e, 0x27, 0x00, 0x55, 0xde, 0x9d, 0x54, 0x52, 0x49, 0x42, 0x45, 0x53
                , 0x2d, 0x42, 0x01, 0x00, 0x00, 0x00, 0x00, 0x35, 0x00, 0x00, 0x00, 0x00, 0x38, 0x98, 0xbc, 0x60
                , 0x00, 0x00, 0x4e, 0x3c, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x04, 0x0a, 0xaf, 0x04
                , 0xe1, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xd7, 0xac, 0xdd, 0x46, 0x3a, 0xf9, 0x4d
                , 0x41, 0xb2, 0xf1, 0xc4, 0x35, 0xd1, 0x93, 0x7e, 0x8a, 0x00, 0x00, 0x00, 0x00, 0x54, 0x52, 0x49
                , 0x42, 0x45, 0x53, 0x2d, 0x45, 0x01, 0x00
        };
        byte[] ipDataByteArray = new byte[ipData.length];
        for (int i = 0; i < ipData.length; i++) {
            ipDataByteArray[i] = (byte) ipData[i];
        }
        IpPacket ipPacket = IpPacketReader.INSTANCE.parse(ipDataByteArray);
        System.out.println(ipPacket);
        System.out.println("======================================");
        byte[] ipPacketArray = IpPacketWriter.INSTANCE.write(ipPacket);
        short[] ipPacketArrayInShort = new short[ipPacketArray.length];
        for (int i = 0; i < ipPacketArray.length; i++) {
            ipPacketArrayInShort[i] = (short) (ipPacketArray[i] & 0xFF);
        }
        String[] hexArray = new String[ipPacketArrayInShort.length];
        for (int i = 0; i < ipPacketArrayInShort.length; i++) {
            hexArray[i] = Integer.toHexString(ipPacketArrayInShort[i]);
        }
        System.out.println(Arrays.toString(hexArray));
    }
}
