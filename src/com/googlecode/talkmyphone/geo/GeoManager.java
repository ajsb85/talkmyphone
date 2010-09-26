package com.googlecode.talkmyphone.geo;

import java.util.List;
import java.util.Locale;

import android.content.Intent;
import android.location.Address;
import android.location.Geocoder;

import com.googlecode.talkmyphone.LocationService;
import com.googlecode.talkmyphone.XmppService;


public class GeoManager {
    
    /** Starts the geolocation service */
    public static void startLocatingPhone() {
        Intent intent = new Intent(XmppService.getInstance(), LocationService.class);
        XmppService.getInstance().startService(intent);
    }

    /** Stops the geolocation service */
    public static void stopLocatingPhone() {
        Intent intent = new Intent(XmppService.getInstance(), LocationService.class);
        XmppService.getInstance().stopService(intent);
    }

    /** Return List of <Address> from searched location */
    public static List<Address> geoDecode(String searchedLocation) {
        try {
            Geocoder geo = new Geocoder(XmppService.getInstance().getBaseContext(), Locale.getDefault());
            List<Address> addresses = geo.getFromLocationName(searchedLocation, 10);
            if (addresses != null && addresses.size() > 0) {
               return addresses;
            }
        }
        catch(Exception ex) {
        }
        
        return null;
    }
    
    /** launches an activity on the url */
    public static void launchExternal(String url) {
        Intent popup = new Intent(XmppService.getInstance(), GeoPopup.class);
        popup.putExtra("url", url);    
        popup.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        
        XmppService.getInstance().startActivity(popup);
    }
}
