package com.ppaass.agent.android.io.protocol;

import com.ppaass.agent.android.io.protocol.ip.*;

import java.nio.ByteBuffer;

public class IpPacketReader {
    public static final IpPacketReader INSTANCE = new IpPacketReader();

    private IpPacketReader() {
    }

    public IpPacket parse(byte[] input) {
        ByteBuffer byteBuffer = ByteBuffer.wrap(input);
        byte versionAndHeaderLength = byteBuffer.get();
        int version = versionAndHeaderLength >> 4;
        IpHeaderVersion ipHeaderVersion = IpHeaderVersion.parse((byte) version);
        if (IpHeaderVersion.V4 != ipHeaderVersion) {
            throw new UnsupportedOperationException("Still not support the other ip version, version = " + version);
        }
        int headerLength = versionAndHeaderLength & 0xf;
        IpV4Header header = new IpV4Header();
        header.setInternetHeaderLength(headerLength);
        byte dsAndEcn = byteBuffer.get();
        int ds = dsAndEcn >> 2;
        int ecn = dsAndEcn & 3;
        IpDifferentiatedServices differentiatedServices = new IpDifferentiatedServices();
        differentiatedServices.setImportance(ds >> 3);
        differentiatedServices.setDelay((ds & 4) != 0);
        differentiatedServices.setHighStream((ds & 2) != 0);
        differentiatedServices.setHighAvailability((ds & 1) != 0);
        header.setDs(differentiatedServices);
        IpExplicitCongestionNotification explicitCongestionNotification = new IpExplicitCongestionNotification();
        explicitCongestionNotification.setLowCost((ecn & 2) != 0);
        explicitCongestionNotification.setResolve(ecn & 1);
        header.setEcn(explicitCongestionNotification);
        header.setTotalLength(byteBuffer.getShort() & 0xFFFF);
        header.setIdentification(byteBuffer.getShort() & 0xFFFF);
        int flagsAndOffset = byteBuffer.getShort();
        int flagsInBit = flagsAndOffset >> 13;
        IpFlags flags = new IpFlags();
        flags.setDf((flagsInBit & 2) != 0);
        flags.setMf((flagsInBit & 1) != 0);
        header.setFlags(flags);
        header.setFragmentOffset(flagsAndOffset & 0x1FFF);
        header.setTtl(byteBuffer.get() & 0xFF);
        header.setProtocol(IpDataProtocol.parse(byteBuffer.get() & 0xFF));
        header.setChecksum(byteBuffer.getShort() & 0xFFFF);
        byte[] sourceAddress = new byte[4];
        byteBuffer.get(sourceAddress);
        header.setSourceAddress(sourceAddress);
        byte[] destinationAddress = new byte[4];
        byteBuffer.get(destinationAddress);
        header.setDestinationAddress(destinationAddress);
        byte[] optionBytes = new byte[headerLength * 4 - IProtocolConst.MIN_IP_HEADER_LENGTH];
        byteBuffer.get(optionBytes);
        header.setOptions(optionBytes);
        byte[] dataBytes = new byte[header.getTotalLength() - headerLength * 4];
        byteBuffer.get(dataBytes);
        byteBuffer.clear();
        if (IpDataProtocol.TCP == header.getProtocol()) {
            return new IpPacket(header, TcpPacketReader.INSTANCE.parse(dataBytes));
        }
        if (IpDataProtocol.UDP == header.getProtocol()) {
            return new IpPacket(header, UdpPacketReader.INSTANCE.parse(dataBytes));
        }
        return new IpPacket(header, IcmpPacketReader.INSTANCE.parse(dataBytes));
    }
}
