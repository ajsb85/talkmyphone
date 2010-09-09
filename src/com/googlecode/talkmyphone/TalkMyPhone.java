package com.googlecode.talkmyphone;

import java.io.IOException;
import java.util.ArrayList;

import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.filter.MessageTypeFilter;
import org.jivesoftware.smack.filter.PacketFilter;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smack.packet.Presence;

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
import android.provider.Contacts;
import android.provider.Settings;
import android.telephony.gsm.SmsManager;
import android.widget.Toast;

public class TalkMyPhone extends Service {

    private String SERVER_HOST;
    private int SERVER_PORT;
    private String SERVICE_NAME;
    private String LOGIN;
    private String PASSWORD;
    private String TO;
    private XMPPConnection m_connection = null;
    private static TalkMyPhone instance = null;
    private MediaPlayer mMediaPlayer;
    private String lastRecipient = null;
    PendingIntent sentPI = null;
    PendingIntent deliveredPI = null;

    private void getPrefs() {
        SharedPreferences prefs = getSharedPreferences("TalkMyPhone", 0);
        SERVER_HOST = prefs.getString("serverHost", "jabber.org");
        SERVER_PORT = 5222;
        SERVICE_NAME = SERVER_HOST;
        LOGIN = prefs.getString("login", "xxxx@jabber.org");
        PASSWORD =  prefs.getString("password", "xxxx");
        TO = prefs.getString("recipient", "xxxx@gmail.com");
    }

    private void initSmsMonitors() {
        String SENT = "SMS_SENT";
        String DELIVERED = "SMS_DELIVERED";

        sentPI = PendingIntent.getBroadcast(this, 0,
            new Intent(SENT), 0);

        deliveredPI = PendingIntent.getBroadcast(this, 0,
            new Intent(DELIVERED), 0);

        //---when the SMS has been sent---
        registerReceiver(new BroadcastReceiver(){
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
        }, new IntentFilter(SENT));

        //---when the SMS has been delivered---
        registerReceiver(new BroadcastReceiver(){
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
        }, new IntentFilter(DELIVERED));
    }

    private void initConnection() throws XMPPException {
        // Initialize connection
        ConnectionConfiguration config =
                new ConnectionConfiguration(SERVER_HOST, SERVER_PORT, SERVICE_NAME);
        m_connection = new XMPPConnection(config);
        m_connection.connect();
        m_connection.login(LOGIN, PASSWORD);
        Presence presence = new Presence(Presence.Type.available);
        m_connection.sendPacket(presence);

        // Register packet listener
        PacketFilter filter = new MessageTypeFilter(Message.Type.chat);
        m_connection.addPacketListener(new PacketListener() {
                public void processPacket(Packet packet) {
                    Message message = (Message) packet;
                    if (message.getFrom().startsWith(TO)) {
                        if (message.getBody() != null) {
                            onCommandReceived(message.getBody());
                        }
                    }
                }
            }, filter);

        // Send welcome message
        send("Welcome to TalkMyPhone. Send \"?\" for getting help");
    }

    private void _onStart() {
        // Get configuration
        if (instance == null)
        {
            instance = this;
            getPrefs();
            initSmsMonitors();
            Uri alert = Settings.System.DEFAULT_RINGTONE_URI ;
            mMediaPlayer = new MediaPlayer();
            try {
                mMediaPlayer.setDataSource(this, alert);
                mMediaPlayer.setAudioStreamType(AudioManager.STREAM_ALARM);
                mMediaPlayer.setLooping(true);
            } catch (IllegalArgumentException e1) {
                e1.printStackTrace();
            } catch (SecurityException e1) {
                e1.printStackTrace();
            } catch (IllegalStateException e1) {
                e1.printStackTrace();
            } catch (IOException e1) {
                e1.printStackTrace();
            }
            try {
                initConnection();
            } catch (XMPPException e) {
                e.printStackTrace();
            }
            Toast.makeText(this, "TalkMyPhone started", Toast.LENGTH_SHORT).show();
        }
    }

    public static TalkMyPhone getInstance() {
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
        instance = null;
        m_connection.disconnect();
        m_connection = null;
        Toast.makeText(this, "TalkMyPhone stopped", Toast.LENGTH_SHORT).show();
    }

    public void send(String message){
        Message msg = new Message(TO, Message.Type.chat);
        msg.setBody(message);
        m_connection.sendPacket(msg);
    }

    public String getContactName (String phoneNumber) {
        String res = phoneNumber;
        ContentResolver resolver = getContentResolver();
        String[] projection = new String[] {
                Contacts.Phones.DISPLAY_NAME,
                Contacts.Phones.NUMBER };
        Uri contactUri = Uri.withAppendedPath(Contacts.Phones.CONTENT_FILTER_URL, Uri.encode(phoneNumber));
        Cursor c = resolver.query(contactUri, projection, null, null, null);
        if (c.moveToFirst()) {
            String name = c.getString(c.getColumnIndex(Contacts.Phones.DISPLAY_NAME));
            res = name;
        }
        return res;
    }

    public void setLastRecipient(String phoneNumber) {
        lastRecipient = phoneNumber;
    }

    private void onCommandReceived(String command) {
        Boolean validCommand = false;
        if (command.equals("?")) {
            validCommand = true;
            StringBuilder builder = new StringBuilder();
            builder.append("Available commands:\n");
            builder.append("- \"?\": shows this help.\n");
            builder.append("- \"reply:message\": send a sms to your last recipient with content message.\n");
            builder.append("- \"sms:number:message\": sends a sms to number with content message.\n");
            builder.append("- \"where\": sends you google map updates about the location of the phone until you send \"stop\"\n");
            builder.append("- \"ring\": rings the phone until you send \"stop\"\n");
            send(builder.toString());
        }
        if (command.startsWith("sms")) {
            validCommand = true;
            String tmp = command.substring(command.indexOf(":") + 1);
            String phoneNumber = tmp.substring(0, tmp.indexOf(":"));
            setLastRecipient(phoneNumber);
            String message = tmp.substring(tmp.indexOf(":") + 1);
            sendSMS(message, phoneNumber);
        }
        if (command.startsWith("reply")) {
            validCommand = true;
            if (lastRecipient == null) {
                send("Error: no recipient registered.");
            } else {
                String message = command.substring(command.indexOf(":") + 1);
                sendSMS(message, lastRecipient);
            }
        }
        if (command.equals("where")) {
            validCommand = true;
            send("Start locating phone");
            startLocatingPhone();
        }
        if (command.equals("stop")) {
            validCommand = true;
            send("Stopping ongoing actions");
            stopLocatingPhone();
            stopRinging();
        }
        if (command.equals("ring")) {
            validCommand = true;
            send("Ringing phone");
            ring();
        }
        if (!validCommand) {
            send('"'+ command + '"' + ": unknown command. Send \"?\" for getting help");
        }
    }

    public void sendSMS(String message, String phoneNumber) {
        send("Sending sms to " + getContactName(phoneNumber));
        SmsManager sms = SmsManager.getDefault();
        ArrayList<String> messages = sms.divideMessage(message);
        for (int i=0; i < messages.size(); i++) {
            sms.sendTextMessage(phoneNumber, null, messages.get(i), sentPI, deliveredPI);
            addSmsToOutbox(message, phoneNumber);
        }
    }

    private void startLocatingPhone() {
        Intent intent = new Intent(this, LocationService.class);
        startService(intent);
    }

    private void stopLocatingPhone() {
        Intent intent = new Intent(this, LocationService.class);
        stopService(intent);
    }

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

    // Stops the phone from ringing
    private void stopRinging() {
        mMediaPlayer.stop();
    }

    private void addSmsToOutbox(String message, String phoneNumber) {
        ContentValues values = new ContentValues();
        values.put("address", phoneNumber);
        values.put("date", System.currentTimeMillis());
        values.put("body", message);
        getContentResolver().insert(Uri.parse("content://sms/outbox"), values);
    }
}
