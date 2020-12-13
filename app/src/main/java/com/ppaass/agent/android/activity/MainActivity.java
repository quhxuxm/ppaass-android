package com.ppaass.agent.android.activity;

import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.ppaass.agent.android.IPpaassConstant;
import com.ppaass.agent.android.R;
import com.ppaass.agent.android.service.PpaassVpnService;

import java.io.IOException;
import java.io.InputStream;

public class MainActivity extends AppCompatActivity {
    private static final int VPN_SERVICE_REQUEST_CODE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Button startVpnButton = this.findViewById(R.id.mainActivityStartVpnButton);
        startVpnButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent startPpaassVpnServiceIntent = VpnService.prepare(MainActivity.this);
                if (startPpaassVpnServiceIntent != null) {
                    startActivityForResult(startPpaassVpnServiceIntent, VPN_SERVICE_REQUEST_CODE);
                    return;
                }
                onActivityResult(VPN_SERVICE_REQUEST_CODE, RESULT_OK, null);
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode != VPN_SERVICE_REQUEST_CODE) {
            return;
        }
        if (resultCode != RESULT_OK) {
            return;
        }
        Intent startPpaassVpnServiceIntent = new Intent(MainActivity.this, PpaassVpnService.class);
        try {
            InputStream agentPrivateKeyStream =
                    MainActivity.this.getResources().openRawResource(R.raw.agentprivatekey);
            byte[] agentPrivateKeyBytes = new byte[agentPrivateKeyStream.available()];
            int readAgentPrivateKeyBytesResult = agentPrivateKeyStream.read(agentPrivateKeyBytes);
            if (readAgentPrivateKeyBytesResult < 0) {
                throw new RuntimeException();
            }
            startPpaassVpnServiceIntent
                    .putExtra(IPpaassConstant.AGENT_PRIVATE_KEY_INTENT_DATA_NAME, agentPrivateKeyBytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try {
            InputStream proxyPublicKeyStream =
                    MainActivity.this.getResources().openRawResource(R.raw.proxypublickey);
            byte[] proxyPublicKeyBytes = new byte[proxyPublicKeyStream.available()];
            int readProxyPublicKeyBytesResult = proxyPublicKeyStream.read(proxyPublicKeyBytes);
            if (readProxyPublicKeyBytesResult < 0) {
                throw new RuntimeException();
            }
            startPpaassVpnServiceIntent
                    .putExtra(IPpaassConstant.PROXY_PUBLIC_KEY_INTENT_DATA_NAME, proxyPublicKeyBytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        MainActivity.this.startService(startPpaassVpnServiceIntent);
    }
}
