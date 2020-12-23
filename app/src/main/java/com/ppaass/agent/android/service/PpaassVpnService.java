package com.ppaass.agent.android.service;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import com.ppaass.agent.android.R;

import java.io.*;

import static com.ppaass.agent.android.IPpaassConstant.VPN_ADDRESS;
import static com.ppaass.agent.android.IPpaassConstant.VPN_ROUTE;

public class PpaassVpnService extends VpnService {
    private FileInputStream vpnInputStream;
    private FileOutputStream vpnOutputStream;
    private ParcelFileDescriptor vpnInterface;
    private PpaassVpnWorker vpnWorker;

    public PpaassVpnService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        byte[] agentPrivateKeyBytes;
        try {
            InputStream agentPrivateKeyStream =
                    this.getResources().openRawResource(R.raw.agentprivatekey);
            agentPrivateKeyBytes = new byte[agentPrivateKeyStream.available()];
            int readAgentPrivateKeyBytesResult = agentPrivateKeyStream.read(agentPrivateKeyBytes);
            if (readAgentPrivateKeyBytesResult < 0) {
                throw new RuntimeException();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        byte[] proxyPublicKeyBytes;
        try {
            InputStream proxyPublicKeyStream =
                    this.getResources().openRawResource(R.raw.proxypublickey);
            proxyPublicKeyBytes = new byte[proxyPublicKeyStream.available()];
            int readProxyPublicKeyBytesResult = proxyPublicKeyStream.read(proxyPublicKeyBytes);
            if (readProxyPublicKeyBytesResult < 0) {
                throw new RuntimeException();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (this.vpnInterface == null) {
            Builder vpnBuilder = new Builder();
            vpnBuilder.addAddress(VPN_ADDRESS, 32);
            vpnBuilder.addRoute(VPN_ROUTE, 0);
            this.vpnInterface =
                    vpnBuilder.setSession(getString(R.string.app_name)).establish();
            final FileDescriptor vpnFileDescriptor = vpnInterface.getFileDescriptor();
            this.vpnInputStream = new FileInputStream(vpnFileDescriptor);
            this.vpnOutputStream = new FileOutputStream(vpnFileDescriptor);
        }
        this.vpnWorker = new PpaassVpnWorker(this.vpnInputStream, this.vpnOutputStream, this, agentPrivateKeyBytes,
                proxyPublicKeyBytes);
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        this.vpnWorker.start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        this.vpnWorker.stop();
        try {
            this.vpnInputStream.close();
            this.vpnOutputStream.close();
            this.vpnInterface.close();
            Log.d(PpaassVpnService.class.getName(), "Close vpn service files.");
        } catch (IOException e) {
            Log.e(PpaassVpnService.class.getName(), "Close vpn service files exception happen.", e);
        }
    }
}
