package com.ppaass.agent.android.io.protocol.ip;

import java.util.Arrays;

public class IpV4Header implements IIpHeader {
    private final IpHeaderVersion version;
    private int internetHeaderLength;
    private IpDifferentiatedServices ds;
    private IpExplicitCongestionNotification ecn;
    private int totalLength;
    private int identification;
    private IpFlags flags;
    private int fragmentOffset;
    private int ttl;
    private IpDataProtocol protocol;
    private int checksum;
    private byte[] sourceAddress;
    private byte[] destinationAddress;
    private byte[] options;

    public IpV4Header() {
        this.version = IpHeaderVersion.V4;
    }

    @Override
    public IpHeaderVersion getVersion() {
        return this.version;
    }

    public int getInternetHeaderLength() {
        return internetHeaderLength;
    }

    public void setInternetHeaderLength(int internetHeaderLength) {
        this.internetHeaderLength = internetHeaderLength;
    }

    public IpDifferentiatedServices getDs() {
        return ds;
    }

    public void setDs(IpDifferentiatedServices ds) {
        this.ds = ds;
    }

    public IpExplicitCongestionNotification getEcn() {
        return ecn;
    }

    public void setEcn(IpExplicitCongestionNotification ecn) {
        this.ecn = ecn;
    }

    public int getTotalLength() {
        return totalLength;
    }

    public void setTotalLength(int totalLength) {
        this.totalLength = totalLength;
    }

    public int getIdentification() {
        return identification;
    }

    public void setIdentification(int identification) {
        this.identification = identification;
    }

    public IpFlags getFlags() {
        return flags;
    }

    public void setFlags(IpFlags flags) {
        this.flags = flags;
    }

    public int getFragmentOffset() {
        return fragmentOffset;
    }

    public void setFragmentOffset(int fragmentOffset) {
        this.fragmentOffset = fragmentOffset;
    }

    public int getTtl() {
        return ttl;
    }

    public void setTtl(int ttl) {
        this.ttl = ttl;
    }

    public IpDataProtocol getProtocol() {
        return protocol;
    }

    public void setProtocol(IpDataProtocol protocol) {
        this.protocol = protocol;
    }

    public int getChecksum() {
        return checksum;
    }

    public void setChecksum(int checksum) {
        this.checksum = checksum;
    }

    public byte[] getSourceAddress() {
        return sourceAddress;
    }

    public void setSourceAddress(byte[] sourceAddress) {
        this.sourceAddress = sourceAddress;
    }

    public byte[] getDestinationAddress() {
        return destinationAddress;
    }

    public void setDestinationAddress(byte[] destinationAddress) {
        this.destinationAddress = destinationAddress;
    }

    public byte[] getOptions() {
        return options;
    }

    public void setOptions(byte[] options) {
        this.options = options;
    }

    @Override
    public String toString() {
        return "IpV4Header{" +
                "version=" + version +
                ", internetHeaderLength=" + internetHeaderLength +
                ", ds=" + ds +
                ", ecn=" + ecn +
                ", totalLength=" + totalLength +
                ", identification=" + identification +
                ", flags=" + flags +
                ", fragmentOffset=" + fragmentOffset +
                ", ttl=" + ttl +
                ", protocol=" + protocol +
                ", checksum=" + checksum +
                ", sourceAddress=" + Arrays.toString(sourceAddress) +
                ", destinationAddress=" + Arrays.toString(destinationAddress) +
                ", options=" + Arrays.toString(options) +
                '}';
    }
}
