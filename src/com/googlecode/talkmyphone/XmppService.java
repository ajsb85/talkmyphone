package com.googlecode.talkmyphone;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.filter.MessageTypeFilter;
import org.jivesoftware.smack.filter.PacketFilter;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Packet;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Address;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.IBinder;
import android.provider.Settings;
import android.text.ClipboardManager;
import android.util.Log;
import android.widget.Toast;

import com.googlecode.talkmyphone.contacts.Contact;
import com.googlecode.talkmyphone.contacts.ContactAddress;
import com.googlecode.talkmyphone.contacts.ContactsManager;
import com.googlecode.talkmyphone.contacts.Phone;
import com.googlecode.talkmyphone.geo.GeoManager;
import com.googlecode.talkmyphone.sms.Sms;
import com.googlecode.talkmyphone.sms.SmsMmsManager;

public class XmppService extends Service {

    private static final int DISCONNECTED = 0;
    private static final int CONNECTING = 1;
    private static final int CONNECTED = 2;

    // Indicates the current state of the service (disconnected/connecting/connected)
    private int mStatus = DISCONNECTED;

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
    private String ringtone = null;
    private boolean canRing;

    // last person who sent sms/who we sent an sms to
    private String lastRecipient = null;

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

    /** Updates the status about the service state (and the statusbar)*/
    private void updateStatus(int status) {
        if (status != mStatus) {
            Notification notification = new Notification();
            switch(status) {
                case CONNECTED:
                    notification = new Notification(
                            R.drawable.status_green,
                            "Connected",
                            System.currentTimeMillis());
                    notification.setLatestEventInfo(
                            getApplicationContext(),
                            "TalkMyPhone",
                            "Connected",
                            contentIntent);
                    break;
                case CONNECTING:
                    notification = new Notification(
                            R.drawable.status_orange,
                            "Connecting...",
                            System.currentTimeMillis());
                    notification.setLatestEventInfo(
                            getApplicationContext(),
                            "TalkMyPhone",
                            "Connecting...",
                            contentIntent);
                    break;
                case DISCONNECTED:
                    notification = new Notification(
                            R.drawable.status_red,
                            "Disconnected",
                            System.currentTimeMillis());
                    notification.setLatestEventInfo(
                            getApplicationContext(),
                            "TalkMyPhone",
                            "Disconnected",
                            contentIntent);
                    break;
                default:
                    break;
            }
            notification.flags |= Notification.FLAG_ONGOING_EVENT;
            notification.flags |= Notification.FLAG_NO_CLEAR;
            stopForegroundCompat(mStatus);
            startForegroundCompat(status, notification);
            mStatus = status;
        }
    }
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
        SmsMmsManager.notifySmsSent = prefs.getBoolean("notifySmsSent", true);
        SmsMmsManager.notifySmsDelivered = prefs.getBoolean("notifySmsDelivered", true);
        ringtone = prefs.getString("ringtone", Settings.System.DEFAULT_RINGTONE_URI.toString());
    }


    /** clears the XMPP connection */
    public void clearConnection() {
        if (mConnection != null) {
            if (mPacketListener != null) {
                mConnection.removePacketListener(mPacketListener);
            }
            // don't try to disconnect if already disconnected
            if (isConnected()) {
                mConnection.disconnect();
            }
        }
        mConnection = null;
        mPacketListener = null;
        mConnectionConfiguration = null;
        updateStatus(DISCONNECTED);
    }

    /** init the XMPP connection */
    public void initConnection() {
        updateStatus(CONNECTING);
        if (mConnectionConfiguration == null) {
            importPreferences();
        }
        mConnection = new XMPPConnection(mConnectionConfiguration);
        try {
            mConnection.connect();
        } catch (XMPPException e) {
            Toast.makeText(this, "Connection failed.", Toast.LENGTH_SHORT).show();
            updateStatus(DISCONNECTED);
            return;
        }
        try {
            mConnection.login(mLogin, mPassword);
        } catch (XMPPException e) {
            Toast.makeText(this, "Login failed", Toast.LENGTH_SHORT).show();
            updateStatus(DISCONNECTED);
            return;
        }
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
        updateStatus(CONNECTED);
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
        canRing = true;
        Uri alert = Uri.parse(ringtone);
        mMediaPlayer = new MediaPlayer();
        try {
            mMediaPlayer.setDataSource(this, alert);
        } catch (Exception e) {
            canRing = false;
        }
        mMediaPlayer.setAudioStreamType(AudioManager.STREAM_ALARM);
        mMediaPlayer.setLooping(true);
    }

    private void _onStart() {
        // Get configuration
        if (instance == null)
        {
            instance = this;

            initNotificationStuff();

            updateStatus(DISCONNECTED);

            // first, clean everything
            clearConnection();
            SmsMmsManager.clearSmsMonitors();
            clearMediaPlayer();
            clearBatteryMonitor();

            // then, re-import preferences
            importPreferences();

            initBatteryMonitor();
            SmsMmsManager.initSmsMonitors();
            initMediaPlayer();
            initConnection();

            if (!isConnected()) {
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
        GeoManager.stopLocatingPhone();

        SmsMmsManager.clearSmsMonitors();
        clearMediaPlayer();
        clearBatteryMonitor();
        clearConnection();

        stopForegroundCompat(mStatus);

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
        if (lastRecipient == null || !phoneNumber.equals(lastRecipient)) {
            lastRecipient = phoneNumber;
            displayLastRecipient(phoneNumber);
        }
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
                builder.append("- \"reply:#message#\": send a sms to your last recipient with content message.\n");
                builder.append("- \"sms:#contact#[:#message#]\": sends a sms to number with content message or display last sent sms.\n");
                builder.append("- \"contact:#contact#\": display informations of a searched contact.\n");
                //builder.append("- \"geo:#address#\": Open Maps or Navigation or Street view on specific address\n");
                builder.append("- \"where\": sends you google map updates about the location of the phone until you send \"stop\"\n");
                builder.append("- \"ring\": rings the phone until you send \"stop\"\n");
                builder.append("- \"copy:#text#\": copy text to clipboard\n");
                builder.append("and you can paste links and open it with the appropriate app\n");
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
                } else if (args.length() > 0) {
                    // todo set number of SMS into parameters
                    // display received SMS and sent SMS
                    contact = args;
                    readSMS(contact, 5);
                } else {
                    displayLastRecipient(lastRecipient);
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
//            else if (command.equals("geo")) {
//                geo(args);
//            } 
            else if (command.equals("contact")) {
                displayContacts(args);
            }
            else if (command.equals("where")) {
                send("Start locating phone");
                GeoManager.startLocatingPhone();
            }
            else if (command.equals("stop")) {
                send("Stopping ongoing actions");
                GeoManager.stopLocatingPhone();
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

    /** sends a SMS to the specified contact */
    public void sendSMS(String message, String contact) {
        if (ContactsManager.isCellPhoneNumber(contact)) {
            send("Sending sms to " + ContactsManager.getContactName(contact));
            SmsMmsManager.sendSMSByPhoneNumber(message, contact);
        } else {
            ArrayList<Phone> mobilePhones = ContactsManager.getMobilePhones(contact);
            if (mobilePhones.size() > 1) {
                send("Specify more details:");

                for (Phone phone : mobilePhones) {
                    send(phone.contactName + " - " + phone.cleanNumber);
                }
            } else if (mobilePhones.size() == 1) {
                Phone phone = mobilePhones.get(0);
                send("Sending sms to " + phone.contactName + " (" + phone.cleanNumber + ")");
                SmsMmsManager.sendSMSByPhoneNumber(message, phone.cleanNumber);
            } else {
                send("No match for \"" + contact + "\"");
            }
        }
    }

    /** reads (count) SMS from all contacts matching pattern */
    public void readSMS(String searchedText, Integer count) {

        ArrayList<Contact> contacts = ContactsManager.getMatchingContacts(searchedText);

        if (contacts.size() > 0) {
            for (Contact contact : contacts) {
                ArrayList<Sms> smsList = SmsMmsManager.getSms(contact.id, count);

                send(contact.name);
                if (smsList.size() > 0) {
                    for (Sms sms : smsList) {
                        send(sms.date.toLocaleString() + " - " + sms.message);
                    }
                    if (smsList.size() < count) {
                        send("Only got " + smsList.size() + " sms");
                    }
                } else {
                    send("No sms found");
                }
            }
        } else {
            send("No match for \"" + searchedText + "\"");
        }
    }

    public void displayLastRecipient(String phoneNumber) {
        if (phoneNumber == null) {
            send("Reply contact is not set");
        } else {
            String contact = ContactsManager.getContactName(phoneNumber);
            if (ContactsManager.isCellPhoneNumber(phoneNumber) && contact.compareTo(phoneNumber) != 0){
                contact += " (" + phoneNumber + ")";
            }
            send("Reply contact is now " + contact);
        }
    }

    /** reads (count) SMS from all contacts matching pattern */
    public void displayContacts(String searchedText) {

        ArrayList<Contact> contacts = ContactsManager.getMatchingContacts(searchedText);

        if (contacts.size() > 0) {
            for (Contact contact : contacts) {
                send(contact.name);

                ArrayList<Phone> mobilePhones = ContactsManager.getPhones(contact.id);
                for (Phone phone : mobilePhones) {
                    send("\t" + phone.label + " - " + phone.cleanNumber);
                }

                ArrayList<ContactAddress> emails = ContactsManager.getEmailAddresses(contact.id);
                for (ContactAddress email : emails) {
                    send("\t" + email.label + " - " + email.address);
                }

                ArrayList<ContactAddress> addresses = ContactsManager.getPostalAddresses(contact.id);
                for (ContactAddress address : addresses) {
                    send("\t" + address.label + " - " + address.address);
                }
            }
        } else {
            send("No match for \"" + searchedText + "\"");
        }
    }

    /** Open geolocalization application */
    private void geo(String text) {
        List<Address> addresses = GeoManager.geoDecode(text);
        if (addresses != null) {
            if (addresses.size() > 1) {
                send("Specify more details:");
                for (Address address : addresses) {
                    StringBuilder addr = new StringBuilder();
                    for (int i = 0; i < address.getMaxAddressLineIndex(); i++) {
                        addr.append(address.getAddressLine(i) + "\n");
                    }
                    send(addr.toString());
                }
            } else if (addresses.size() == 1) {
                GeoManager.launchExternal(addresses.get(0).getLatitude() + "," + addresses.get(0).getLongitude());
            }
        } else {
            send("No match for \"" + text + "\"");
        }
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
        if (canRing && audioManager.getStreamVolume(AudioManager.STREAM_ALARM) != 0) {
            try {
                mMediaPlayer.prepare();
            } catch (Exception e) {
                canRing = false;
                send("Unable to ring, change the ringtone in the options");
            }
            mMediaPlayer.start();
        }
    }

    /** Stops the phone from ringing */
    private void stopRinging() {
        if (canRing) {
            mMediaPlayer.stop();
        }
    }
}
