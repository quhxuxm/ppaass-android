package com.ppaass.agent.android;

import com.ppaass.common.util.UUIDUtil;

public interface IPpaassConstant {
    String AGENT_PRIVATE_KEY_INTENT_DATA_NAME = "agentPrivateKey";
    String PROXY_PUBLIC_KEY_INTENT_DATA_NAME = "proxyPublicKey";
    String VPN_ADDRESS = "99.99.99.99";
    String VPN_ROUTE = "0.0.0.0";
    String PROXY_SERVER_ADDRESS = "45.63.92.64";
    //String PROXY_SERVER_ADDRESS = "10.175.4.220";
    //int PROXY_SERVER_PORT = 6888;
    int PROXY_SERVER_PORT = 80;
    String USER_TOKEN = "QH_VPN_ANDROID";
    String AGENT_INSTANCE_ID = UUIDUtil.INSTANCE.generateUuid();
}
