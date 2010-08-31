package com.service.TalkMyPhone;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;

public class Main extends Activity {

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        Button prefBtn = (Button) findViewById(R.id.Preferences);
        prefBtn.setOnClickListener(new OnClickListener() {

                public void onClick(View v) {
                    Intent settingsActivity = new Intent(getBaseContext(), Preferences.class);
                    startActivity(settingsActivity);
                }
        });

        Button startStopButton = (Button) findViewById(R.id.StartStop);
        startStopButton.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    Intent intent = new Intent(".TalkMyPhone.ACTION");
                    if (TalkMyPhone.getInstance() == null) {
                        startService(intent);
                    }
                    else {
                        stopService(intent);
                    }
                }
        });

    }


}
