package com.googlecode.talkmyphone.sms;

import java.util.ArrayList;
import java.util.Date;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.telephony.gsm.SmsManager;

import com.googlecode.talkmyphone.Tools;
import com.googlecode.talkmyphone.XmppService;

public class SmsMmsManager {
    // intents for sms sending
    public static PendingIntent sentPI = null;
    public static PendingIntent deliveredPI = null;
    public static BroadcastReceiver sentSmsReceiver = null;
    public static BroadcastReceiver deliveredSmsReceiver = null;
    public static boolean notifySmsSent;
    public static boolean notifySmsDelivered;
    
    /** clear the sms monitoring related stuff */
    public static void clearSmsMonitors() {
        if (sentSmsReceiver != null) {
            XmppService.getInstance().unregisterReceiver(sentSmsReceiver);
        }
        if (deliveredSmsReceiver != null) {
            XmppService.getInstance().unregisterReceiver(deliveredSmsReceiver);
        }
        sentPI = null;
        deliveredPI = null;
        sentSmsReceiver = null;
        deliveredSmsReceiver = null;
    }

    /** reinit sms monitors (that tell the user the status of the sms) */
    public static void initSmsMonitors() {
        if (notifySmsSent) {
            String SENT = "SMS_SENT";
            sentPI = PendingIntent.getBroadcast(XmppService.getInstance(), 0,
                new Intent(SENT), 0);
            sentSmsReceiver = new BroadcastReceiver(){
                @Override
                public void onReceive(Context arg0, Intent arg1) {
                    switch (getResultCode())
                    {
                        case Activity.RESULT_OK:
                            XmppService.getInstance().send("SMS sent");
                            break;
                        case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
                            XmppService.getInstance().send("Generic failure");
                            break;
                        case SmsManager.RESULT_ERROR_NO_SERVICE:
                            XmppService.getInstance().send("No service");
                            break;
                        case SmsManager.RESULT_ERROR_NULL_PDU:
                            XmppService.getInstance().send("Null PDU");
                            break;
                        case SmsManager.RESULT_ERROR_RADIO_OFF:
                            XmppService.getInstance().send("Radio off");
                            break;
                    }
                }
            };
            XmppService.getInstance().registerReceiver(sentSmsReceiver, new IntentFilter(SENT));
        }
        if (notifySmsDelivered) {
            String DELIVERED = "SMS_DELIVERED";
            deliveredPI = PendingIntent.getBroadcast(XmppService.getInstance(), 0,
                    new Intent(DELIVERED), 0);
            deliveredSmsReceiver = new BroadcastReceiver(){
                @Override
                public void onReceive(Context arg0, Intent arg1) {
                    switch (getResultCode())
                    {
                        case Activity.RESULT_OK:
                            XmppService.getInstance().send("SMS delivered");
                            break;
                        case Activity.RESULT_CANCELED:
                            XmppService.getInstance().send("SMS not delivered");
                            break;
                    }
                }
            };
            XmppService.getInstance().registerReceiver(deliveredSmsReceiver, new IntentFilter(DELIVERED));
        }
    }

    /** Sends a sms to the specified phone number */
    public static void sendSMSByPhoneNumber(String message, String phoneNumber) {
        SmsManager sms = SmsManager.getDefault();
        ArrayList<String> messages = sms.divideMessage(message);
        for (int i=0; i < messages.size(); i++) {
            sms.sendTextMessage(phoneNumber, null, messages.get(i), sentPI, deliveredPI);
            addSmsToSentBox(message, phoneNumber);
        }
    }

    /**
     * Returns a ArrayList of <Sms> with count sms where the contactId match the argument
     */
    public static ArrayList<Sms> getSms(Long contactId, Integer count) {
        ArrayList<Sms> res = new ArrayList<Sms>();
        
        if(null != contactId) {
            Uri mSmsQueryUri = Uri.parse("content://sms/inbox");
            String columns[] = new String[] { "person", "body", "date", "status"};
            Cursor c = XmppService.getInstance().getContentResolver().query(mSmsQueryUri, columns, "person = " + contactId, null, null);

            if (c.getCount() > 0) {
                Integer i = 0;
                for (boolean hasData = c.moveToFirst() ; hasData && i++ < count ; hasData = c.moveToNext()) {
                    Date date = new Date();
                    date.setTime(Long.parseLong(Tools.getString(c ,"date")));
                    Sms sms = new Sms();
                    sms.date = date;
                    sms.message = Tools.getString(c ,"body");
                    res.add( sms );
                }
            }
            c.close();
        }
        return res;
    }

    /** Adds the text of the message to the sent box */
    public static void addSmsToSentBox(String message, String phoneNumber) {
        ContentValues values = new ContentValues();
        values.put("address", phoneNumber);
        values.put("date", System.currentTimeMillis());
        values.put("body", message);
        XmppService.getInstance().getContentResolver().insert(Uri.parse("content://sms/sent"), values);
    }
}
