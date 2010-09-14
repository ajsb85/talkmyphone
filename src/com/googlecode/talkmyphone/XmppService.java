package com.googlecode.talkmyphone;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

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
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.location.Address;
import android.location.Geocoder;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.IBinder;
import android.provider.Contacts;
import android.provider.Settings;
import android.provider.Contacts.People;
import android.telephony.gsm.SmsManager;
import android.widget.Toast;

public class XmppService extends Service {

    private String SERVER_HOST;
    private int SERVER_PORT;
    private String SERVICE_NAME;
    private String LOGIN;
    private String PASSWORD;
    private String TO;
    private XMPPConnection m_connection = null;
    private static XmppService instance = null;
    private MediaPlayer mMediaPlayer;
    private String lastRecipient = null;
    PendingIntent sentPI = null;
    PendingIntent deliveredPI = null;
    private BroadcastReceiver mBatInfoReceiver = new BroadcastReceiver(){
        @Override
        public void onReceive(Context arg0, Intent intent) {
          int level = intent.getIntExtra("level", 0);
          send("Battery level "+String.valueOf(level)+"%");
        }
    };

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

        Timer t = new Timer();
        t.schedule(new TimerTask() {
                @Override
                public void run() {
                    Presence presence = new Presence(Presence.Type.available);
                    m_connection.sendPacket(presence);
                }
            }, 0, 60*1000);

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
            registerReceiver(mBatInfoReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            Toast.makeText(this, "TalkMyPhone started", Toast.LENGTH_SHORT).show();
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

    private Dictionary<Long, String> getContacts(String name) {
        Dictionary<Long, String> res = new Hashtable<Long, String>();
        ContentResolver resolver = getContentResolver();
        String[] projection = new String[] { People._ID, People.NAME };

        Uri contactUri = Uri.withAppendedPath(People.CONTENT_FILTER_URI, Uri.encode(name));
        Cursor c = resolver.query(contactUri, projection, null, null, null);
        if (c.moveToFirst()) {
            do {
                Long id = getLong(c,People._ID);
                if (null != id) {
                    String contactName = getString(c,People.NAME);
                    if(null != contactName) {
                        res.put(id, contactName);
                    }
                }
            } while (c.moveToNext());
        }
        return res;
    }

    public void showNumbers(String contact) {
        Dictionary<Long, String> contacts = getContacts(contact);
        ContentResolver resolver = getContentResolver();

        Enumeration<Long> e = contacts.keys();
        while( e. hasMoreElements() ){
            Long id = e.nextElement();
            send(contacts.get(id));

            Uri personUri = ContentUris.withAppendedId(People.CONTENT_URI, id);
            Uri phonesUri = Uri.withAppendedPath(personUri, People.Phones.CONTENT_DIRECTORY);
            String[] proj = new String[] {Contacts.Phones.NUMBER, Contacts.Phones.LABEL, Contacts.Phones.TYPE};
            Cursor c2 = resolver.query(phonesUri, proj, null, null, null);
            if (c2.moveToFirst()) {
                do {
                    String number = cleanNumber(getString(c2,Contacts.Phones.NUMBER));
                    String label = getString(c2,Contacts.Phones.LABEL);
                    int type = getLong(c2,Contacts.Phones.TYPE).intValue();

                    if (label != null && label.compareTo("") != 0)
                    {
                        number += " - " + label;
                    }
                    else if (type != Contacts.Phones.TYPE_CUSTOM)
                    {
                        number += " - " + Contacts.Phones.getDisplayLabel(this.getBaseContext(), type, "");
                    }
                    send("\t" + number);
                } while (c2.moveToNext());
            }
        }
    }

    private Long getLong(Cursor c, String col) {
        return c.getLong(c.getColumnIndex(col));
    }

    private String cleanNumber(String num) {
        return num.replace("(", "").replace(")", "").replace(" ", "").replace("+33", "0");
    }

    private String getString(Cursor c, String col) {
        return c.getString(c.getColumnIndex(col));
    }

    public void setLastRecipient(String phoneNumber) {
        lastRecipient = phoneNumber;
    }

    private void onCommandReceived(String command) {
        try
        {
            if (command.equals("?")) {
                send("Available commands:");
                send("- \"?\": shows this help.");
                send("- \"reply:message\": send a sms to your last recipient with content message.");
                send("- \"sms:number:message\": sends a sms to number with content message.");
                send("- \"readsms:contact[:number]\": read X sms of a specific contact.");
                send("- \"number:contact\": show phone number of a specific contact.");
                send("- \"where\": sends you google map updates about the location of the phone until you send \"stop\"");
                send("- \"ring\": rings the phone until you send \"stop\"");
                send("- \"browse:url\": browse an url");
                send("- \"map:address\": launch Google Map on a location");
                send("- \"nav:address\": launch Google Navigation on a location");
                send("- \"street:address\": launch Google Street View on a location");
                }
            else if (command.startsWith("sms")) {
                String tmp = command.substring(command.indexOf(":") + 1);
                String phoneNumber = tmp.substring(0, tmp.indexOf(":"));
                setLastRecipient(phoneNumber);
                String message = tmp.substring(tmp.indexOf(":") + 1);
                sendSMS(message, phoneNumber);
            }
            else if (command.startsWith("reply")) {
                if (lastRecipient == null) {
                    send("Error: no recipient registered.");
                } else {
                    String message = command.substring(command.indexOf(":") + 1);
                    sendSMS(message, lastRecipient);
                }
            }
            else if (command.startsWith("number")) {
                String name = command.substring(command.indexOf(":") + 1);
                showNumbers(name);
            }
            else if (command.startsWith("readsms")) {
                String tmp = command.substring(command.indexOf(":") + 1);
                String name = tmp.substring(0, tmp.indexOf(":"));
                int count = 1000;
                try{
                    count = Integer.parseInt(tmp.substring(tmp.indexOf(":") + 1));
                }
                catch (Exception e) {
                }
                readSMS(name, count);
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
            else if (command.startsWith("browse")) {
                String url = command.substring(command.indexOf(":") + 1);
                browse(url);
            }
            else if (command.startsWith("map")) {
                String url = command.substring(command.indexOf(":") + 1);
                maps(url);
            }
            else if (command.startsWith("nav")) {
                String url = command.substring(command.indexOf(":") + 1);
                navigate(url);
            }
            else if (command.startsWith("street")) {
                String url = command.substring(command.indexOf(":") + 1);
                streetView(url);
            }
            else {
                send('"'+ command + '"' + ": unknown command. Send \"?\" for getting help");
            }
        }
        catch(Exception ex)
        {
            send("Error : " + ex);
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

    public void readSMS(String contact, Integer count) {

        Dictionary<Long, String> contacts = getContacts(contact);
        ContentResolver resolver = getContentResolver();

        Enumeration<Long> e = contacts.keys();
        while( e. hasMoreElements() ){
            Long id = e.nextElement();
            if(null != id)
            {
                Uri mSmsQueryUri = Uri.parse("content://sms/inbox");
                String columns[] = new String[] { "person", "body", "date", "status"};
                Cursor c = resolver.query(mSmsQueryUri, columns, "person = " + id, null, null);

                if (c.getCount() > 0) {
                    send(contacts.get(id));
                    Integer i = 0;
                    for (boolean hasData = c.moveToFirst() ; hasData && i++ < count ; hasData = c.moveToNext()) {
                        Date date = new Date();
                        date.setTime(Long.parseLong(getString(c ,"date")));
                        send( date.toLocaleString() + " - " + getString(c ,"body"));
                    }
                }
                c.close();
            }
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

    private void browse(String url) {
        try
        {
            if(!url.contains("//"))
            {
                url = "http://" + url;
            }
            launchExternal(url);
            send("Browsing URL \"" + url + "\".");
        }
        catch(Exception ex)
        {
            send("URL \"" + url + "\" not supported");
        }
    }

    private void maps(String url) {
        try
        {
            if(!url.startsWith("geo:"))
            {
                url = "geo:0,0?q=" + url.replace(" ", "+");
            }
            launchExternal(url);
            send("Map on \"" + url + "\".");
        }
        catch(Exception ex)
        {
            send("\"" + url + "\" not supported");
        }
    }

    private void navigate(String url) {
        try
        {
            if(!url.startsWith("google.navigation:"))
            {
                url = "google.navigation:q=" + url.replace(" ", "+");
            }
            launchExternal(url);
            send("Navigate to \"" + url + "\".");
        }
        catch(Exception ex)
        {
            send("\"" + url + "\" not supported");
        }
    }

    private void streetView(String url) {
        try
        {
            Geocoder geo = new Geocoder(getBaseContext(), Locale.getDefault());
            List<Address> addresses = geo.getFromLocationName(url, 10);
            if (addresses.size() > 1) {
                send("Specify more details:");
                for (Address address : addresses) {
                    StringBuilder addr = new StringBuilder();
                    for (int i = 0; i < address.getMaxAddressLineIndex(); i++) {
                        addr.append(address.getAddressLine(i) + "\n");
                    }
                    send(addr.toString());
                }
            }
            else if (addresses.size() == 1) {
                Address address = addresses.get(0);
                launchExternal("google.streetview:cbll=" + address.getLatitude() + "," + address.getLongitude());
                StringBuilder addr = new StringBuilder();
                for (int i = 0; i < address.getMaxAddressLineIndex(); i++) {
                    addr.append(address.getAddressLine(i) + "\n");
                }
                send("Street View on \"" + addr + "\".");
            }
        }
        catch(Exception ex)
        {
            send("\"" + url + "\" not supported");
        }
    }

    private void launchExternal(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
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
        getContentResolver().insert(Uri.parse("content://sms/sent"), values);
    }
}
