package com.googlecode.talkmyphone;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;

import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.filter.MessageTypeFilter;
import org.jivesoftware.smack.filter.PacketFilter;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Packet;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.IBinder;
import android.provider.Settings;
import android.telephony.gsm.SmsManager;
import android.text.ClipboardManager;
import android.util.Log;
import android.widget.Toast;

import com.googlecode.talkmyphone.contacts.Contact;
import com.googlecode.talkmyphone.contacts.ContactsManager;
import com.googlecode.talkmyphone.contacts.Phone;

public class XmppService extends Service {

    private static final int NOTIFICATION_ID = 1;

    // Service instance
    private static XmppService instance = null;

    // XMPP connection
    private String mLogin;
    private String mPassword;
    private String mTo;
    private ConnectionConfiguration mConnectionConfiguration = null;
    private XMPPConnection mConnection = null;
    private PacketListener mPacketListener = null;
    private boolean notifyApplicationConnection;

    // ring
    private MediaPlayer mMediaPlayer = null;

    // last person who sent sms/who we sent an sms to
    private String lastRecipient = null;

    // intents for sms sending
    PendingIntent sentPI = null;
    PendingIntent deliveredPI = null;
    BroadcastReceiver sentSmsReceiver = null;
    BroadcastReceiver deliveredSmsReceiver = null;
    private boolean notifySmsSent;
    private boolean notifySmsDelivered;

    // battery
    private BroadcastReceiver mBatInfoReceiver = null;
    private boolean notifyBattery;

    // notification stuff
    @SuppressWarnings("unchecked")
    private static final Class[] mStartForegroundSignature = new Class[] {
        int.class, Notification.class};
    @SuppressWarnings("unchecked")
    private static final Class[] mStopForegroundSignature = new Class[] {
        boolean.class};
    private NotificationManager mNM;
    private Method mStartForeground;
    private Method mStopForeground;
    private Object[] mStartForegroundArgs = new Object[2];
    private Object[] mStopForegroundArgs = new Object[1];
    private PendingIntent contentIntent = null;
    private Notification notification = null;

    /**
     * This is a wrapper around the startForeground method, using the older
     * APIs if it is not available.
     */
    void startForegroundCompat(int id, Notification notification) {
        // If we have the new startForeground API, then use it.
        if (mStartForeground != null) {
            mStartForegroundArgs[0] = Integer.valueOf(id);
            mStartForegroundArgs[1] = notification;
            try {
                mStartForeground.invoke(this, mStartForegroundArgs);
            } catch (InvocationTargetException e) {
                // Should not happen.
                Log.w("ApiDemos", "Unable to invoke startForeground", e);
            } catch (IllegalAccessException e) {
                // Should not happen.
                Log.w("ApiDemos", "Unable to invoke startForeground", e);
            }
            return;
        }
        // Fall back on the old API.
        setForeground(true);
        mNM.notify(id, notification);
    }

    /**
     * This is a wrapper around the stopForeground method, using the older
     * APIs if it is not available.
     */
    void stopForegroundCompat(int id) {
        // If we have the new stopForeground API, then use it.
        if (mStopForeground != null) {
            mStopForegroundArgs[0] = Boolean.TRUE;
            try {
                mStopForeground.invoke(this, mStopForegroundArgs);
            } catch (InvocationTargetException e) {
                // Should not happen.
                Log.w("ApiDemos", "Unable to invoke stopForeground", e);
            } catch (IllegalAccessException e) {
                // Should not happen.
                Log.w("ApiDemos", "Unable to invoke stopForeground", e);
            }
            return;
        }

        // Fall back on the old API.  Note to cancel BEFORE changing the
        // foreground state, since we could be killed at that point.
        mNM.cancel(id);
        setForeground(false);
    }

    /**
     * This makes the 2 previous wrappers possible
     */
    private void initNotificationStuff() {
        mNM = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        try {
            mStartForeground = getClass().getMethod("startForeground",
                    mStartForegroundSignature);
            mStopForeground = getClass().getMethod("stopForeground",
                    mStopForegroundSignature);
        } catch (NoSuchMethodException e) {
            // Running on an older platform.
            mStartForeground = mStopForeground = null;
        }
        contentIntent =
            PendingIntent.getActivity(
                    this, 0, new Intent(this, MainScreen.class), 0);
        notification = new Notification(
                R.drawable.icon,
                "TalkMyPhone is running.",
                System.currentTimeMillis());
    }

    /** imports the preferences */
    private void importPreferences() {
        SharedPreferences prefs = getSharedPreferences("TalkMyPhone", 0);
        String serverHost = prefs.getString("serverHost", "");
        int serverPort = prefs.getInt("serverPort", 0);
        String serviceName = prefs.getString("serviceName", "");
        mConnectionConfiguration = new ConnectionConfiguration(serverHost, serverPort, serviceName);
        mTo = prefs.getString("notifiedAddress", "");
        mPassword =  prefs.getString("password", "");
        boolean useDifferentAccount = prefs.getBoolean("useDifferentAccount", false);
        if (useDifferentAccount) {
            mLogin = prefs.getString("login", "");
        } else{
            mLogin = mTo;
        }
        notifyApplicationConnection = prefs.getBoolean("notifyApplicationConnection", true);
        notifyBattery = prefs.getBoolean("notifyBattery", true);
        notifySmsSent = prefs.getBoolean("notifySmsSent", true);
        notifySmsDelivered = prefs.getBoolean("notifySmsDelivered", true);
    }

    /** clear the sms monitoring related stuff */
    private void clearSmsMonitors() {
        if (sentSmsReceiver != null) {
            unregisterReceiver(sentSmsReceiver);
        }
        if (deliveredSmsReceiver != null) {
            unregisterReceiver(deliveredSmsReceiver);
        }
        sentPI = null;
        deliveredPI = null;
        sentSmsReceiver = null;
        deliveredSmsReceiver = null;
    }

    /** reinit sms monitors (that tell the user the status of the sms) */
    private void initSmsMonitors() {
        if (notifySmsSent) {
            String SENT = "SMS_SENT";
            sentPI = PendingIntent.getBroadcast(this, 0,
                new Intent(SENT), 0);
            sentSmsReceiver = new BroadcastReceiver(){
                @Override
                public void onReceive(Context arg0, Intent arg1) {
                    switch (getResultCode())
                    {
                        case Activity.RESULT_OK:
                            send("SMS sent");
                            break;
                        case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
                            send("Generic failure");
                            break;
                        case SmsManager.RESULT_ERROR_NO_SERVICE:
                            send("No service");
                            break;
                        case SmsManager.RESULT_ERROR_NULL_PDU:
                            send("Null PDU");
                            break;
                        case SmsManager.RESULT_ERROR_RADIO_OFF:
                            send("Radio off");
                            break;
                    }
                }
            };
            registerReceiver(sentSmsReceiver, new IntentFilter(SENT));
        }
        if (notifySmsDelivered) {
            String DELIVERED = "SMS_DELIVERED";
            deliveredPI = PendingIntent.getBroadcast(this, 0,
                    new Intent(DELIVERED), 0);
            deliveredSmsReceiver = new BroadcastReceiver(){
                @Override
                public void onReceive(Context arg0, Intent arg1) {
                    switch (getResultCode())
                    {
                        case Activity.RESULT_OK:
                            send("SMS delivered");
                            break;
                        case Activity.RESULT_CANCELED:
                            send("SMS not delivered");
                            break;
                    }
                }
            };
            registerReceiver(deliveredSmsReceiver, new IntentFilter(DELIVERED));
        }
    }

    /** clears the XMPP connection */
    public void clearConnection() {
        if (mConnection != null) {
            if (mPacketListener != null) {
                mConnection.removePacketListener(mPacketListener);
            }
            //commented since this seems to bug
            //mConnection.disconnect();
        }
        mConnection = null;
        mPacketListener = null;
        mConnectionConfiguration = null;
    }

    /** init the XMPP connection */
    public void initConnection() {
        if (mConnectionConfiguration == null) {
            importPreferences();
        }
        mConnection = new XMPPConnection(mConnectionConfiguration);
        try {
            Toast.makeText(this, "Connecting to server...", Toast.LENGTH_SHORT).show();
            mConnection.connect();
        } catch (XMPPException e) {
            Toast.makeText(this, "Connection failed.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Toast.makeText(this, "Loging in...", Toast.LENGTH_SHORT).show();
            mConnection.login(mLogin, mPassword);
        } catch (XMPPException e) {
            Toast.makeText(this, "Log in failed", Toast.LENGTH_SHORT).show();
            return;
        }
        /*
        Timer t = new Timer();
        t.schedule(new TimerTask() {
                @Override
                public void run() {
                    Presence presence = new Presence(Presence.Type.available);
                    mConnection.sendPacket(presence);
                }
            }, 0, 60*1000);
        */
        PacketFilter filter = new MessageTypeFilter(Message.Type.chat);
        mPacketListener = new PacketListener() {
            public void processPacket(Packet packet) {
                Message message = (Message) packet;
                
                if (    message.getFrom().toLowerCase().startsWith(mTo.toLowerCase() + "/")
                    && !message.getFrom().equals(mConnection.getUser()) // filters self-messages
                ) {
                    if (message.getBody() != null) {
                        onCommandReceived(message.getBody());
                    }
                }
            }
        };
        mConnection.addPacketListener(mPacketListener, filter);
        // Send welcome message
        if (notifyApplicationConnection) {
            send("Welcome to TalkMyPhone. Send \"?\" for getting help");
        }
    }

    /** returns true if the service is correctly connected */
    public boolean isConnected() {
        return    (mConnection != null
                && mConnection.isConnected()
                && mConnection.isAuthenticated());
    }

    /** clear the battery monitor*/
    private void clearBatteryMonitor() {
        if (mBatInfoReceiver != null) {
            unregisterReceiver(mBatInfoReceiver);
        }
        mBatInfoReceiver = null;
    }

    /** init the battery stuff */
    private void initBatteryMonitor() {
        if (notifyBattery) {
            mBatInfoReceiver = new BroadcastReceiver(){
                private int lastPercentageNotified = -1;
                @Override
                public void onReceive(Context arg0, Intent intent) {
                    int level = intent.getIntExtra("level", 0);
                    if (lastPercentageNotified == -1) {
                        notifyAndSavePercentage(level);
                    } else {
                        if (level != lastPercentageNotified && level % 5 == 0) {
                            notifyAndSavePercentage(level);
                        }
                    }
                }
                private void notifyAndSavePercentage(int level) {
                    send("Battery level " + level + "%");
                    lastPercentageNotified = level;
                }
            };
            registerReceiver(mBatInfoReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        }
    }

    /** clears the media player */
    private void clearMediaPlayer() {
        if (mMediaPlayer != null) {
            mMediaPlayer.stop();
        }
        mMediaPlayer = null;
    }

    /** init the media player */
    private void initMediaPlayer() {
        Uri alert = Settings.System.DEFAULT_RINGTONE_URI ;
        mMediaPlayer = new MediaPlayer();
        try {
            mMediaPlayer.setDataSource(this, alert);
            mMediaPlayer.setAudioStreamType(AudioManager.STREAM_ALARM);
            mMediaPlayer.setLooping(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void _onStart() {
        // Get configuration
        if (instance == null)
        {
            instance = this;

            initNotificationStuff();
            notification.setLatestEventInfo(
                    getApplicationContext(),
                    "TalkMyPhone",
                    "Application is running",
                    contentIntent);
            // Makes the service virtually impossible to kill
            this.startForegroundCompat(NOTIFICATION_ID, notification);

            // first, clean everything
            clearConnection();
            clearSmsMonitors();
            clearMediaPlayer();
            clearBatteryMonitor();

            // then, re-import preferences
            importPreferences();

            initBatteryMonitor();
            initSmsMonitors();
            initMediaPlayer();
            initConnection();

            if (isConnected()) {
                Toast.makeText(this, "TalkMyPhone started", Toast.LENGTH_SHORT).show();
            } else {
                onDestroy();
            }
        }
    }

    public static XmppService getInstance() {
        return instance;
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return null;
    }

    @Override
    public void onStart(Intent intent, int startId) {
        _onStart();
    };

    @Override
    public void onDestroy() {
        stopLocatingPhone();

        clearSmsMonitors();
        clearMediaPlayer();
        clearBatteryMonitor();
        clearConnection();

        stopForegroundCompat(NOTIFICATION_ID);

        instance = null;

        Toast.makeText(this, "TalkMyPhone stopped", Toast.LENGTH_SHORT).show();
    }

    /** sends a message to the user */
    public void send(String message) {
        if (isConnected()) {
            Message msg = new Message(mTo, Message.Type.chat);
            msg.setBody(message);
            mConnection.sendPacket(msg);
        }
    }


    public void setLastRecipient(String phoneNumber) {
        lastRecipient = phoneNumber;
    }

    /** handles the different commands */
    private void onCommandReceived(String commandLine) {
        try {
            String command;
            String args;
            if (-1 != commandLine.indexOf(":")) {
                command = commandLine.substring(0, commandLine.indexOf(":"));
                args = commandLine.substring(commandLine.indexOf(":") + 1);
            } else {
                command = commandLine;
                args = "";
            }

            // Not case sensitive commands
            command = command.toLowerCase();

            if (command.equals("?")) {
                StringBuilder builder = new StringBuilder();
                builder.append("Available commands:\n");
                builder.append("- \"?\": shows this help.\n");
                builder.append("- \"reply:message\": send a sms to your last recipient with content message.\n");
                builder.append("- \"sms:contact[:message]\": sends a sms to number with content message or display last sent sms.\n");
                builder.append("- \"where\": sends you google map updates about the location of the phone until you send \"stop\"\n");
                builder.append("- \"ring\": rings the phone until you send \"stop\"\n");
                builder.append("- \"copy:text\": copy text to clipboard\n");
                builder.append("- paste links, open it with the appropriate app\n");
                send(builder.toString());
            }
            else if (command.equals("sms")) {
                int separatorPos = args.indexOf(":");
                String contact = null;
                String message = null;
                if (-1 != separatorPos) {
                    contact = args.substring(0, separatorPos);
                    setLastRecipient(contact);
                    message = args.substring(separatorPos + 1);
                    sendSMS(message, contact);
                } else {
                    // todo set number of SMS into parameters
                    // display received SMS and sent SMS
                    contact = args;
                    readSMS(contact, 5);
                }
            }
            else if (command.equals("reply")) {
                if (lastRecipient == null) {
                    send("Error: no recipient registered.");
                } else {
                    sendSMS(args, lastRecipient);
                }
            }
            else if (command.equals("copy")) {
                copyToClipboard(args);
            }
            else if (command.equals("where")) {
                send("Start locating phone");
                startLocatingPhone();
            }
            else if (command.equals("stop")) {
                send("Stopping ongoing actions");
                stopLocatingPhone();
                stopRinging();
            }
            else if (command.equals("ring")) {
                send("Ringing phone");
                ring();
            }
            else if (command.equals("http")) {
                open("http:" + args);
            }
            else if (command.equals("https")) {
                open("https:" + args);
            }
            else {
                send('"'+ commandLine + '"' + ": unknown command. Send \"?\" for getting help");
            }
        } catch (Exception ex) {
            send("Error : " + ex);
        }
    }

    /** Sends a sms to the specified phone number */
    public void sendSMSByPhoneNumber(String message, String phoneNumber) {
        SmsManager sms = SmsManager.getDefault();
        ArrayList<String> messages = sms.divideMessage(message);
        send("Sending sms to " + ContactsManager.getContactName(phoneNumber));
        for (int i=0; i < messages.size(); i++) {
            if (i >= 1) {
                send("sending part " + i + "/" + messages.size() + " of splitted message");
            }
            sms.sendTextMessage(phoneNumber, null, messages.get(i), sentPI, deliveredPI);
            addSmsToSentBox(message, phoneNumber);
        }
    }

    /** sends a SMS to the specified contact */
    public void sendSMS(String message, String contact) {
        if (ContactsManager.isCellPhoneNumber(contact)) {
            sendSMSByPhoneNumber(message, contact);
        } else {
            ArrayList<Phone> mobilePhones = ContactsManager.getMobilePhones(contact);
            if (mobilePhones.size() > 1) {
                send("Specify more details:");

                for (Phone phone : mobilePhones) {
                    send(phone.contactName + " - " + phone.cleanNumber);
                }
            } else if (mobilePhones.size() == 1) {
                Phone phone = mobilePhones.get(0);
                sendSMSByPhoneNumber(message, phone.cleanNumber);
            } else {
                send("No match for \"" + contact + "\"");
            }
        }
    }

    /** reads (count) SMS from all contacts matching pattern */
    public void readSMS(String searchedText, Integer count) {

        ArrayList<Contact> contacts = ContactsManager.getMatchingContacts(searchedText);

        if (contacts.size() > 0) {
            ContentResolver resolver = getContentResolver();

            for (Contact contact : contacts) {
                if(null != contact.id) {
                    Uri mSmsQueryUri = Uri.parse("content://sms/inbox");
                    String columns[] = new String[] { "person", "body", "date", "status"};
                    Cursor c = resolver.query(mSmsQueryUri, columns, "person = " + contact.id, null, null);

                    send(contact.name);
                    if (c.getCount() > 0) {
                        Integer i = 0;
                        for (boolean hasData = c.moveToFirst() ; hasData && i++ < count ; hasData = c.moveToNext()) {
                            Date date = new Date();
                            date.setTime(Long.parseLong(Tools.getString(c ,"date")));
                            send( date.toLocaleString() + " - " + Tools.getString(c ,"body"));
                        }
                        if (i < count) {
                            send("Only got " + i + " sms");
                        }
                    } else {
                        send("No sms found");
                    }
                    c.close();
                }
            }
        } else {
            send("No match for \"" + searchedText + "\"");
        }
    }

    /** Starts the geolocation service */
    private void startLocatingPhone() {
        Intent intent = new Intent(this, LocationService.class);
        startService(intent);
    }

    /** Stops the geolocation service */
    private void stopLocatingPhone() {
        Intent intent = new Intent(this, LocationService.class);
        stopService(intent);
    }

    /** copy text to clipboard */
    private void copyToClipboard(String text) {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setText(text);
            send("Text copied");
        }
        catch(Exception ex) {
            send("Clipboard access failed");
        }
    }

    /** lets the user choose an activity compatible with the url */
    private void open(String url) {
        Intent target = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        Intent intent = Intent.createChooser(target, "TalkMyPhone: choose an activity");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    /** makes the phone ring */
    private void ring() {
        final AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) != 0) {
            try {
                mMediaPlayer.prepare();
            } catch (IllegalStateException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mMediaPlayer.start();
        }
    }

    /** Stops the phone from ringing */
    private void stopRinging() {
        mMediaPlayer.stop();
    }

    /** Adds the text of the message to the sent box */
    private void addSmsToSentBox(String message, String phoneNumber) {
        ContentValues values = new ContentValues();
        values.put("address", phoneNumber);
        values.put("date", System.currentTimeMillis());
        values.put("body", message);
        getContentResolver().insert(Uri.parse("content://sms/sent"), values);
    }

}
