package com.service.TalkMyPhone;

import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.filter.MessageTypeFilter;
import org.jivesoftware.smack.filter.PacketFilter;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Packet;
import org.jivesoftware.smack.packet.Presence;

import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import android.provider.Contacts;
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

        //Register packet listener
        PacketFilter filter = new MessageTypeFilter(Message.Type.chat);

        m_connection.addPacketListener(new PacketListener() {
                public void processPacket(Packet packet) {
                    Message message = (Message) packet;
                    if (message.getBody() != null) {
                        onCommandReceived(message.getBody());
                    }
                }
            }, filter);
        send(" ");
        send("Welcome to TalkMyPhone. Send \"?\" for getting help");
    }

    private void _onStart(){
        instance = this;
        // Get configuration
        getPrefs();
        try {
            initConnection();
        } catch (XMPPException e) {
            e.printStackTrace();
        }
        Toast.makeText(this, "TalkMyPhone started", Toast.LENGTH_SHORT).show();
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
        if (command.equals("?")) {
            send("TalkMyPhone does not currently support user interaction. See next versions!");
        } else {
            send("Unknown command. Send \"?\" for getting help");
        }
    }
}
