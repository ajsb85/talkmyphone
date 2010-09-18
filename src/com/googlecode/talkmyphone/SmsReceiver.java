package com.googlecode.talkmyphone;

import com.googlecode.talkmyphone.contacts.ContactsManager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.telephony.gsm.SmsMessage;


public class SmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences("TalkMyPhone", 0);
        boolean notifyIncomingSms = prefs.getBoolean("notifyIncomingSms", false);

        if (notifyIncomingSms) {
            Bundle bundle = intent.getExtras();
            SmsMessage[] msgs = null;
            if (bundle != null)
            {
                XmppService service = XmppService.getInstance();
                if (service != null)
                {
                    StringBuilder builder = new StringBuilder();
                    Object[] pdus = (Object[]) bundle.get("pdus");
                    msgs = new SmsMessage[pdus.length];
                    for (int i=0; i<msgs.length; i++) {
                        msgs[i] = SmsMessage.createFromPdu((byte[])pdus[i]);
                        builder.append("SMS from ");
                        builder.append(ContactsManager.getContactName(msgs[i].getOriginatingAddress()));
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
}
