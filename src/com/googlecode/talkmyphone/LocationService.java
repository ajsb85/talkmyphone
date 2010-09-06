package com.googlecode.talkmyphone;

/*
 * Source code of this class originally written by Kevin AN <anyupu@gmail.com>
 * from the project android-phonefinder: http://code.google.com/p/android-phonefinder/
 */

import java.lang.reflect.Method;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

public class LocationService extends Service {

    LocationManager locationManager;
    @Override
    public IBinder onBind(Intent arg0) {
        return null;
    }

    @Override
    public void onCreate() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
    }

    /*
     * http://www.maximyudin.com/2008/12/07/android/vklyuchenievyklyuchenie-gps-na-g1-programmno/
     */
    private boolean getGPSStatus()
    {
        String allowedLocationProviders =
            Settings.System.getString(getContentResolver(),
            Settings.System.LOCATION_PROVIDERS_ALLOWED);

        if (allowedLocationProviders == null) {
            allowedLocationProviders = "";
        }

        return allowedLocationProviders.contains(LocationManager.GPS_PROVIDER);
    }

    private void setGPSStatus(boolean pNewGPSStatus)
    {
        String allowedLocationProviders =
            Settings.System.getString(getContentResolver(),
            Settings.System.LOCATION_PROVIDERS_ALLOWED);

        if (allowedLocationProviders == null) {
            allowedLocationProviders = "";
        }

        boolean networkProviderStatus =
            allowedLocationProviders.contains(LocationManager.NETWORK_PROVIDER);

        allowedLocationProviders = "";
        if (networkProviderStatus == true) {
            allowedLocationProviders += LocationManager.NETWORK_PROVIDER;
        }
        if (pNewGPSStatus == true) {
            allowedLocationProviders += "," + LocationManager.GPS_PROVIDER;
        }

        Settings.System.putString(getContentResolver(),
            Settings.System.LOCATION_PROVIDERS_ALLOWED, allowedLocationProviders);

        try
        {
            Method m =
                locationManager.getClass().getMethod("updateProviders", new Class[] {});
            m.setAccessible(true);
            m.invoke(locationManager, new Object[]{});
        }
        catch(Exception e)
        {
            Log.e("%s:%s", e.getClass().getName());
        }
        return;
    }

    public void sendLocationUpdate(Location location) {
        StringBuilder builder = new StringBuilder();
        builder.append("Location found.\n");
        builder.append("accuracy: " + location.getAccuracy() + "meters \n");
        builder.append("altitude: " + location.getAltitude() + "\n");
        builder.append("speed:" + location.getSpeed() + "\n");
        builder.append("provided by: " + location.getProvider() + "\n");
        builder.append("http://maps.google.com/maps?q=" + location.getLatitude() + "," + location.getLongitude() + "\n");
        TalkMyPhone.getInstance().send(builder.toString());
    }

    public void onStart(final Intent intent, int startId) {
       super.onStart(intent, startId);
       try{
            if( ! getGPSStatus() )
                setGPSStatus(true);
            }
       catch(Exception e)
        {
            Log.e("%s:%s", e.getClass().getName());
        }
       LocationListener locationListener = new LocationListener() {
            public void onLocationChanged(Location location) {
                sendLocationUpdate(location);

                locationManager.removeUpdates(this);
            }

            public void onStatusChanged(String arg0, int arg1, Bundle arg2) {
                // TODO Auto-generated method stub

            }

            public void onProviderDisabled(String arg0) {
            }

            public void onProviderEnabled(String arg0) {
            }
        };

        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, locationListener);

        Location location = locationManager.getLastKnownLocation("gps");
        if (location == null)
            location = locationManager.getLastKnownLocation("network");
        if (location != null){
            sendLocationUpdate(location);
        }
    }

    public void onDestroy() {
    }

}
