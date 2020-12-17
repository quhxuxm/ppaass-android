package com.ppaass.agent.android.activity;

import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.widget.Button;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.ppaass.agent.android.R;
import com.ppaass.agent.android.service.PpaassVpnService;

public class MainActivity extends AppCompatActivity {
    private static final int VPN_SERVICE_REQUEST_CODE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Button startVpnButton = this.findViewById(R.id.mainActivityStartVpnButton);
        startVpnButton.setOnClickListener(v -> {
            Intent startPpaassVpnServiceIntent = VpnService.prepare(MainActivity.this);
            if (startPpaassVpnServiceIntent != null) {
                startActivityForResult(startPpaassVpnServiceIntent, VPN_SERVICE_REQUEST_CODE);
                return;
            }
            onActivityResult(VPN_SERVICE_REQUEST_CODE, RESULT_OK, null);
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != VPN_SERVICE_REQUEST_CODE) {
            return;
        }
        if (resultCode != RESULT_OK) {
            return;
        }
        Intent startPpaassVpnServiceIntent = new Intent(MainActivity.this, PpaassVpnService.class);
        MainActivity.this.startService(startPpaassVpnServiceIntent);
    }
}
