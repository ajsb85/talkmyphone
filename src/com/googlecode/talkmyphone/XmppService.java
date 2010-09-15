package com.googlecode.talkmyphone;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;

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
import android.provider.Contacts.People;
import android.provider.Settings;
import android.telephony.gsm.SmsManager;
import android.text.ClipboardManager;
import android.widget.Toast;

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
                if (message.getFrom().startsWith(mTo + "/")) {
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

    /** gets the contact display name of the specified phone number */
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

    /** gets all matching contacts with their ID */
    private Dictionary<Long, String> getContacts(String name) {
        Dictionary<Long, String> res = new Hashtable<Long, String>();
        
        if (name.compareTo("") != 0)
        {
            ContentResolver resolver = getContentResolver();
            String[] projection = new String[] { People._ID, People.NAME };
    
            Uri contactUri = Uri.withAppendedPath(People.CONTENT_FILTER_URI, Uri.encode(name));
            Cursor c = resolver.query(contactUri, projection, null, null, null);
            for (boolean hasData = c.moveToFirst() ; hasData ; hasData = c.moveToNext()) {
                Long id = getLong(c,People._ID);
                if (null != id) {
                    String contactName = getString(c,People.NAME);
                    if(null != contactName) {
                        res.put(id, contactName);
                    }
                }
            }
            c.close();
        }
        return res;
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
                send("- \"copy:text\": copy text to clipboard");
                send("- \"map:address\": launch Google Map on a location");
                send("- \"nav:address\": launch Google Navigation on a location (if available)");
                send("- \"street:address\": launch Google Street View on a location (if available)");
            }
            else if (command.equals("sms")) {
                String phoneNumber = args.substring(0, args.indexOf(":"));
                setLastRecipient(phoneNumber);
                String message = args.substring(args.indexOf(":") + 1);
                sendSMS(message, phoneNumber);
            }
            else if (command.equals("reply")) {
                if (lastRecipient == null) {
                    send("Error: no recipient registered.");
                } else {
                    sendSMS(args, lastRecipient);
                }
            }
            else if (command.equals("number")) {
                showNumbers(args);
            }
            else if (command.equals("copy")) {
                copyToClipboard(args);
            }
            else if (command.equals("readsms")) {
                int count = 10;
                String name = args;
                
                if (-1 != args.indexOf(":")) {
                    name = args.substring(0, args.indexOf(":"));
                    try {
                        count = Integer.parseInt(args.substring(args.indexOf(":") + 1));
                    } catch (Exception e) {
                    }    
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
            else if (command.equals("browse")) {
                browse(args);
            }
            else if (command.equals("map")) {
                maps(args);
            }
            else if (command.equals("nav")) {
                navigate(args);
            }
            else if (command.equals("street")) {
                streetView(args);
            }
            else {
                send('"'+ commandLine + '"' + ": unknown command. Send \"?\" for getting help");
            }
        } catch (Exception ex) {
            send("Error : " + ex);
        }
    }

    /** display phone numbers from all contacts matching pattern */
    public void showNumbers(String contact) {
        Dictionary<Long, String> contacts = getContacts(contact);

        if (contacts.size() > 0) {
            ContentResolver resolver = getContentResolver();
            Enumeration<Long> e = contacts.keys();
            while( e. hasMoreElements() ){
                Long id = e.nextElement();
                send(contacts.get(id));
    
                Uri personUri = ContentUris.withAppendedId(People.CONTENT_URI, id);
                Uri phonesUri = Uri.withAppendedPath(personUri, People.Phones.CONTENT_DIRECTORY);
                String[] proj = new String[] {Contacts.Phones.NUMBER, Contacts.Phones.LABEL, Contacts.Phones.TYPE};
                Cursor c = resolver.query(phonesUri, proj, null, null, null);
               
                for (boolean hasData = c.moveToFirst() ; hasData ; hasData = c.moveToNext()) {
                    
                    String number = cleanNumber(getString(c,Contacts.Phones.NUMBER));
                    String label = getString(c,Contacts.Phones.LABEL);
                    int type = getLong(c,Contacts.Phones.TYPE).intValue();

                    if (label != null && label.compareTo("") != 0)
                    {
                        number += " - " + label;
                    }
                    else if (type != Contacts.Phones.TYPE_CUSTOM)
                    {
                        number += " - " + Contacts.Phones.getDisplayLabel(this.getBaseContext(), type, "");
                    }
                    send("\t" + number);
                }
                c.close();
            }
        } else {
            send("No match for \"" + contact + "\"");
        }
    }

    /** sends a SMS to the specified phone number */
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

    /** sends (count) SMS to all contacts matching pattern */
    public void readSMS(String contact, Integer count) {

        Dictionary<Long, String> contacts = getContacts(contact);
        
        if (contacts.size() > 0) {
            ContentResolver resolver = getContentResolver();

            Enumeration<Long> e = contacts.keys();
            while( e.hasMoreElements() ){
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
        } else {
            send("No match for \"" + contact + "\"");
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
        }
        catch(Exception ex) {
            send("Clipboard access failed");
        }
    }
    
    /** launches the browser on the specified url */
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

    /** launches google maps on the specified url */
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

    /** launches navigate on the specified url */
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

    /** launches streetview on the specified url */
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

    /** launches an activity on the url */
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
