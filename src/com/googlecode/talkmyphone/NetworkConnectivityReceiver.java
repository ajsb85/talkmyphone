package com.googlecode.talkmyphone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

public class NetworkConnectivityReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {

        XmppService service = XmppService.getInstance();
        if (service != null) {
            NetworkInfo network = (NetworkInfo) intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);
                // if no network, disconnect
            if (network == null || !network.isAvailable()) {
                service.clearConnection();
            } else {
                // connect if not already connected
                if (network.isConnected() && !service.isConnected()) {
                    service.initConnection();
                }
            }
        }
    }
}
