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

    // Service instance
    private static XmppService instance = null;

    // XMPP connection
    private String mLogin;
    private String mPassword;
    private String mTo;
    private ConnectionConfiguration mConnectionConfiguration;
    private XMPPConnection mConnection = null;
    private boolean notifyApplicationConnection;

    // ring
    private MediaPlayer mMediaPlayer;

    // last person who sent sms/who we sent an sms to
    private String lastRecipient = null;

    // intents for sms sending
    PendingIntent sentPI = null;
    PendingIntent deliveredPI = null;
    private boolean notifySmsSent;
    private boolean notifySmsDelivered;

    // battery
    private BroadcastReceiver mBatInfoReceiver;
    private boolean notifyBattery;

    /** import the preferences */
    private void importPreferences() {
        SharedPreferences prefs = getSharedPreferences("TalkMyPhone", 0);
        String serverHost = prefs.getString("serverHost", "");
        int serverPort = prefs.getInt("serverPort", 0);
        String serviceName = prefs.getString("serviceName", "");
        mConnectionConfiguration = new ConnectionConfiguration(serverHost, serverPort, serviceName);
        mLogin = prefs.getString("login", "");
        mPassword =  prefs.getString("password", "");
        mTo = prefs.getString("recipient", "");
        notifyApplicationConnection = prefs.getBoolean("notifyApplicationConnection", true);
        notifyBattery = prefs.getBoolean("notifyBattery", true);
        notifySmsSent = prefs.getBoolean("notifySmsSent", true);
        notifySmsDelivered = prefs.getBoolean("notifySmsDelivered", true);
    }

    /** init sms monitors (that tell the user the status of the sms) */
    private void initSmsMonitors() {
        if (notifySmsSent) {
            String SENT = "SMS_SENT";
            sentPI = PendingIntent.getBroadcast(this, 0,
                new Intent(SENT), 0);
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
        }
        if (notifySmsDelivered) {
            String DELIVERED = "SMS_DELIVERED";
            deliveredPI = PendingIntent.getBroadcast(this, 0,
                    new Intent(DELIVERED), 0);
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
    }

    /** init the XMPP connection */
    private void initConnection() {
        // Initialize connection
        mConnection = new XMPPConnection(mConnectionConfiguration);
        try {
            mConnection.connect();
            mConnection.login(mLogin, mPassword);
        } catch (XMPPException e) {
            e.printStackTrace();
        }

        Timer t = new Timer();
        t.schedule(new TimerTask() {
                @Override
                public void run() {
                    Presence presence = new Presence(Presence.Type.available);
                    mConnection.sendPacket(presence);
                }
            }, 0, 60*1000);

        // Register packet listener
        PacketFilter filter = new MessageTypeFilter(Message.Type.chat);
        mConnection.addPacketListener(new PacketListener() {
                public void processPacket(Packet packet) {
                    Message message = (Message) packet;
                    if (message.getFrom().startsWith(mTo)) {
                        if (message.getBody() != null) {
                            onCommandReceived(message.getBody());
                        }
                    }
                }
            }, filter);

        // Send welcome message
        if (notifyApplicationConnection) {
            send("Welcome to TalkMyPhone. Send \"?\" for getting help");
        }
    }

    /** init the battery stuff */
    private void initBatteryStuff() {
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

    /** init the media player */
    private void initMediaPlayerStuff() {
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
            importPreferences();
            initSmsMonitors();
            initBatteryStuff();
            initMediaPlayerStuff();
            initConnection();
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
        mConnection.disconnect();
        mConnection = null;
        stopRinging();
        stopLocatingPhone();
        Toast.makeText(this, "TalkMyPhone stopped", Toast.LENGTH_SHORT).show();
    }

    public void send(String message){
        Message msg = new Message(mTo, Message.Type.chat);
        msg.setBody(message);
        mConnection.sendPacket(msg);
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
                //send("- \"readsms:contact[:number]\": read X sms of a specific contact.");
                //send("- \"number:contact\": show phone number of a specific contact.");
                send("- \"where\": sends you google map updates about the location of the phone until you send \"stop\"");
                send("- \"ring\": rings the phone until you send \"stop\"");
                send("- \"browse:url\": browse an url");
                send("- \"map:address\": launch Google Map on a location");
                send("- \"nav:address\": launch Google Navigation on a location (if available)");
                send("- \"street:address\": launch Google Street View on a location (if available)");
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
            /*
            else if (command.startsWith("number")) {
                String name = command.substring(command.indexOf(":") + 1);
                showNumbers(name);
            }
            */
            /*
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
            */
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
            if (i >= 1) {
                send("sending part " + i + "/" + messages.size() + " of splitted message");
            }
            sms.sendTextMessage(phoneNumber, null, messages.get(i), sentPI, deliveredPI);
            addSmsToSentBox(message, phoneNumber);
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

    private void browse(String url) {
        try {
            if(!url.contains("//")) {
                url = "http://" + url;
            }
            launchExternal(url);
            send("Browsing URL \"" + url + "\".");
        }
        catch(Exception ex) {
            send("URL \"" + url + "\" not supported");
        }
    }

    private void maps(String url) {
        try {
            if(!url.startsWith("geo:")) {
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
            if(!url.startsWith("google.navigation:")) {
                url = "google.navigation:q=" + url.replace(" ", "+");
            }
            launchExternal(url);
            send("Navigate to \"" + url + "\".");
        }
        catch(Exception ex) {
            send("\"" + url + "\" not supported");
        }
    }

    private void streetView(String url) {
        try {
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
        catch(Exception ex) {
            send("\"" + url + "\" not supported");
        }
    }

    private void launchExternal(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
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
