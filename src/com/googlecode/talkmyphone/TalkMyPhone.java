package com.googlecode.talkmyphone;

import java.io.IOException;

import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.filter.MessageTypeFilter;
import org.jivesoftware.smack.filter.PacketFilter;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smack.packet.Presence;

import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.IBinder;
import android.provider.Contacts;
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

    private void getPrefs() {
        SharedPreferences prefs = getSharedPreferences("TalkMyPhone", 0);
        SERVER_HOST = prefs.getString("serverHost", "jabber.org");
        SERVER_PORT = 5222;
        SERVICE_NAME = SERVER_HOST;
        LOGIN = prefs.getString("login", "xxxx@jabber.org");
        PASSWORD =  prefs.getString("password", "xxxx");
        TO = prefs.getString("recipient", "xxxx@gmail.com");
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
                    if (message.getBody() != null) {
                        onCommandReceived(message.getBody());
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

    private void onCommandReceived(String command) {
        Boolean validCommand = false;
        if (command.equals("?")) {
            validCommand = true;
            StringBuilder builder = new StringBuilder();
            builder.append("Available commands:\n");
            builder.append("- \"?\": shows this help.\n");
            builder.append("- \"sms:number:message\": sends a sms to number with content message.\n");
            builder.append("- \"where\": sends you google map updates about the location of the phone until you send \"stop\"");
            send(builder.toString());
        }
        if (command.startsWith("sms")) {
            validCommand = true;
            String[] sms = command.split(":");
            String phoneNumber = sms[1];
            StringBuilder builder = new StringBuilder();
            for (int i = 2; i < sms.length; i++) {
                builder.append(sms[i]);
                if (i <= sms.length) {
                    builder.append(":");
                }
            }
            String message = builder.toString();
            sendSMS(message, phoneNumber);
            send("Sms sent.");
        }
        if (command.equals("where")) {
            validCommand = true;
            send("Start locating phone");
            startLocatingPhone();
        }
        if (command.equals("stop")) {
            validCommand = true;
            send("Stop locating phone");
            stopLocatingPhone();
        }
        if (command.equals("ring")) {
            validCommand = true;
            send("Ringing phone");
            try {
                ring();
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            } catch (SecurityException e) {
                e.printStackTrace();
            } catch (IllegalStateException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (!validCommand) {
            send('"'+ command + '"' + ": unknown command. Send \"?\" for getting help");
        }
    }

    public void sendSMS(String message, String phoneNumber) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, TalkMyPhone.class), 0);
        SmsManager sms = SmsManager.getDefault();
        sms.sendTextMessage(phoneNumber, null, message, pi, null);
    }

    private void startLocatingPhone() {
        Intent intent = new Intent(this, LocationService.class);
        startService(intent);
    }

    private void stopLocatingPhone() {
        Intent intent = new Intent(this, LocationService.class);
        stopService(intent);
    }

    private void ring() throws IllegalArgumentException, SecurityException, IllegalStateException, IOException {
         Uri alert = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
         MediaPlayer mMediaPlayer = new MediaPlayer();
         mMediaPlayer.setDataSource(this, alert);
         final AudioManager audioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
         if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) != 0) {
                    mMediaPlayer.setAudioStreamType(AudioManager.STREAM_ALARM);
                    mMediaPlayer.setLooping(true);
                    mMediaPlayer.prepare();
                    mMediaPlayer.start();
          }

    }
}
