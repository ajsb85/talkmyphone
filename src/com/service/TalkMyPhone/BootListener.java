package com.service.TalkMyPhone;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;


public class BootListener extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences("TalkMyPhone", 0);
        boolean startAtBoot = prefs.getBoolean("startAtBoot", false);
        if (startAtBoot) {
            Intent serviceIntent = new Intent(".TalkMyPhone.ACTION");
            context.startService(serviceIntent);
        }
    }
}
