package com.no5.releaseinfo;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Opened only by an explicit user action. Never an automatic modal on app start or update. */
public final class AboutActivity extends Activity {
    public static void open(Activity activity) { activity.startActivity(new Intent(activity, AboutActivity.class)); }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(20 * getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, pad);
        Button back = new Button(this); back.setText(ReleaseUiText.text(this,"Zurück")); back.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View view) { finish(); } }); content.addView(back);
        TextView title = new TextView(this); title.setText(ReleaseUiText.text(this,"Version · Was ist neu")); title.setTextSize(24); content.addView(title);
        final TextView details = new TextView(this); details.setText(ReleaseInfo.details(this)); details.setTextSize(16); details.setTextIsSelectable(true); content.addView(details);
        if(!ReleaseInfo.details(this, true).equals(ReleaseInfo.details(this))) {
            final Button more = new Button(this); more.setText(ReleaseUiText.text(this,"Alle Änderungen anzeigen"));
            more.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View view) {
                details.setText(ReleaseInfo.details(AboutActivity.this, true)); more.setVisibility(android.view.View.GONE);
            } }); content.addView(more);
        }
        ScrollView scroll = new ScrollView(this); scroll.addView(content); setContentView(scroll);
    }
}
