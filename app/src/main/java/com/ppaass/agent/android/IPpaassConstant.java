package com.ppaass.agent.android;

import com.ppaass.agent.android.io.process.tcp.TcpIoLoop;
import io.netty.util.AttributeKey;

public interface IPpaassConstant {
    String AGENT_PRIVATE_KEY_INTENT_DATA_NAME = "agentPrivateKey";
    String PROXY_PUBLIC_KEY_INTENT_DATA_NAME = "proxyPublicKey";
    String VPN_ADDRESS = "99.99.99.99";
    String VPN_ROUTE = "0.0.0.0";
    String PROXY_SERVER_ADDRESS = "192.168.31.11";
    int PROXY_SERVER_PORT = 8090;
    String USER_TOKEN= "QH_VPN_ANDROID";
}
