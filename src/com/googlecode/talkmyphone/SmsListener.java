package com.googlecode.talkmyphone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.gsm.SmsMessage;


public class SmsListener extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle bundle = intent.getExtras();
        SmsMessage[] msgs = null;
        if (bundle != null)
        {
            TalkMyPhone service = TalkMyPhone.getInstance();
            if (service != null)
            {
                StringBuilder builder = new StringBuilder();
                Object[] pdus = (Object[]) bundle.get("pdus");
                msgs = new SmsMessage[pdus.length];
                for (int i=0; i<msgs.length; i++) {
                    msgs[i] = SmsMessage.createFromPdu((byte[])pdus[i]);
                    builder.append("SMS from ");
                    builder.append(service.getContactName(msgs[i].getOriginatingAddress()));
                    builder.append(": ");
                    builder.append(msgs[i].getMessageBody().toString());
                    builder.append("\n");
                    service.setLastRecipient(msgs[i].getOriginatingAddress());
                }
                service.send(builder.toString());

            }
        }
    }
}
