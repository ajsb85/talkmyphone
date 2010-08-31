package com.service.TalkMyPhone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;

public class SmsListener extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle bundle = intent.getExtras();
        SmsMessage[] msgs = null;
        String str = "";
        if (bundle != null)
        {
            TalkMyPhone service = TalkMyPhone.getInstance();
            if (service != null)
            {
                Object[] pdus = (Object[]) bundle.get("pdus");
                msgs = new SmsMessage[pdus.length];
                for (int i=0; i<msgs.length; i++) {
                    msgs[i] = SmsMessage.createFromPdu((byte[])pdus[i]);
                    str += "SMS from ";
                    str += service.getContactName(msgs[i].getOriginatingAddress());
                    str += ": ";
                    str += msgs[i].getMessageBody().toString();
                    str += "\n";
                }
                service.send(str);
            }
        }
    }
}
