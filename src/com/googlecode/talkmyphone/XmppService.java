package com.googlecode.talkmyphone;

import java.io.IOException;
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
import android.widget.Toast;

import com.googlecode.talkmyphone.Contacts.Contact;
import com.googlecode.talkmyphone.Contacts.ContactsManager;
import com.googlecode.talkmyphone.Contacts.Phone;

public class XmppService extends Service {

    // Service instance
    private static XmppService instance = null;

    // XMPP connection
    private String mLogin;
    private String mPassword;
    private String mTo;
    private ConnectionConfiguration mConnectionConfiguration = null;
    private XMPPConnection mConnection = null;
    private PacketListener mPacketListener;
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

    /** import the preferences */
    private void importPreferences() {
        SharedPreferences prefs = getSharedPreferences("TalkMyPhone", 0);
        String serverHost = "talk.google.com";
        int serverPort = 5222;
        String serviceName = "gmail.com";
        mConnectionConfiguration = new ConnectionConfiguration(serverHost, serverPort, serviceName);
        mLogin = prefs.getString("login", "");
        mPassword =  prefs.getString("password", "");
        mTo = prefs.getString("login", "");
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
    private void clearConnection() {
        if (mConnection != null) {
            if (mPacketListener != null) {
                mConnection.removePacketListener(mPacketListener);
            }
            mConnection.disconnect();
        }
        mConnection = null;
        mPacketListener = null;
        mConnectionConfiguration = null;
    }

    /** init the XMPP connection */
    private void initConnection() {
        mConnection = new XMPPConnection(mConnectionConfiguration);
        try {
            mConnection.connect();
            mConnection.login(mLogin, mPassword);
        } catch (XMPPException e) {
            e.printStackTrace();
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
                if (message.getFrom().startsWith(mTo + "/")
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

    /** Reconnects using the current preferences (assumes the service is started)*/
    public void reConnect() {
        mConnection.disconnect();
        try {
            mConnection.connect();
            mConnection.login(mLogin, mPassword);
        } catch (XMPPException e) {
            e.printStackTrace();
        }
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
                @Override
                public void onReceive(Context arg0, Intent intent) {
                  int level = intent.getIntExtra("level", 0);
                  send("Battery level " + level + "%");
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

            // first, clean everything
            clearConnection();
            clearSmsMonitors();
            clearMediaPlayer();
            clearBatteryMonitor();

            // then, re-import preferences
            importPreferences();

            // finally, init everything
            initSmsMonitors();
            initBatteryMonitor();
            initMediaPlayer();
            initConnection();

            if (mConnection.isAuthenticated()) {
                Toast.makeText(this, "TalkMyPhone started", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "TalkMyPhone failed to authenticate", Toast.LENGTH_SHORT).show();
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
    public void onCreate() {
        super.onCreate();
        _onStart();
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

        instance = null;

        Toast.makeText(this, "TalkMyPhone stopped", Toast.LENGTH_SHORT).show();
    }

    /** sends a message to the user */
    public void send(String message){
        Message msg = new Message(mTo, Message.Type.chat);
        msg.setBody(message);
        mConnection.sendPacket(msg);
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

                    if (c.getCount() > 0) {
                        send(contact.name);
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
