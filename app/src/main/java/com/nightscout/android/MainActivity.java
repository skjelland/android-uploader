package com.nightscout.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.gms.analytics.GoogleAnalytics;
import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.nightscout.android.dexcom.SyncingService;
import com.nightscout.android.exceptions.Reporter;
import com.nightscout.android.preferences.PreferenceKeys;
import com.nightscout.android.preferences.PreferencesValidator;
import com.nightscout.android.settings.SettingsActivity;
import com.nightscout.android.ui.AppContainer;
import com.nightscout.android.wearables.Pebble;
import com.nightscout.core.dexcom.TrendArrow;
import com.nightscout.core.dexcom.Utils;
import com.nightscout.core.download.GlucoseUnits;
import com.nightscout.core.preferences.NightscoutPreferences;
import com.nightscout.core.utils.GlucoseReading;
import com.nightscout.core.utils.RestUriUtils;

import org.joda.time.DateTime;
import org.joda.time.Minutes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Date;
import java.util.List;

import javax.inject.Inject;

import butterknife.ButterKnife;
import butterknife.InjectView;

import static com.nightscout.core.dexcom.SpecialValue.getEGVSpecialValue;
import static com.nightscout.core.dexcom.SpecialValue.isSpecialValue;
import static org.joda.time.Duration.standardMinutes;

public class MainActivity extends Activity {
    private static final Logger log = LoggerFactory.getLogger(MainActivity.class);

    // Receivers
    private CGMStatusReceiver mCGMStatusReceiver;
    private ToastReceiver toastReceiver;

    // Member components
    private Handler mHandler = new Handler();
    private Context mContext;
    private String mJSONData;
    private long lastRecordTime = -1;
    private long receiverOffsetFromUploader = 0;

    @Inject NightscoutPreferences preferences;
    @Inject AppContainer appContainer;
    @Inject Reporter reporter;

    // Analytics mTracker
    private Tracker mTracker;

    // UI components

    @InjectView(R.id.webView) WebView mWebView;
    @InjectView(R.id.sgValue) TextView mTextSGV;
    @InjectView(R.id.timeAgo) TextView mTextTimestamp;
    private StatusBarIcons statusBarIcons;
    private Pebble pebble;

    // TODO: should try and avoid use static
    public static int batLevel = 0;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        log.debug("OnCreate called.");

        Nightscout app = Nightscout.get(this);
        app.inject(this);
        reporter.initialize();

        ViewGroup group = appContainer.get(this);
        getLayoutInflater().inflate(R.layout.activity_main, group);

        ButterKnife.inject(this);

        migrateToNewStyleRestUris();
        ensureSavedUrisAreValid();
        ensureIUnderstandDialogDisplayed();

        mTracker = ((Nightscout) getApplicationContext()).getTracker();

        mContext = getApplicationContext();

        // Register USB attached/detached and battery changes intents
        IntentFilter deviceStatusFilter = new IntentFilter();
        deviceStatusFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        deviceStatusFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        deviceStatusFilter.addAction(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(mDeviceStatusReceiver, deviceStatusFilter);

        // Register Broadcast Receiver for response messages from mSyncingServiceIntent service
        mCGMStatusReceiver = new CGMStatusReceiver();
        IntentFilter filter = new IntentFilter(CGMStatusReceiver.PROCESS_RESPONSE);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        registerReceiver(mCGMStatusReceiver, filter);

        toastReceiver = new ToastReceiver();
        IntentFilter toastFilter = new IntentFilter(ToastReceiver.ACTION_SEND_NOTIFICATION);
        toastFilter.addCategory(Intent.CATEGORY_DEFAULT);
        registerReceiver(toastReceiver, toastFilter);

        mTextSGV.setTag(R.string.display_sgv, -1);
        mTextSGV.setTag(R.string.display_trend, 0);
        mWebView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        WebSettings webSettings = mWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAppCacheEnabled(true);
        webSettings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webSettings.setUseWideViewPort(false);
        mWebView.setVerticalScrollBarEnabled(false);
        mWebView.setHorizontalScrollBarEnabled(false);
        mWebView.setBackgroundColor(0);
        mWebView.loadUrl("file:///android_asset/index.html");

        statusBarIcons = (StatusBarIcons) getFragmentManager().findFragmentById(R.id.iconLayout);

        // If app started due to android.hardware.usb.action.USB_DEVICE_ATTACHED intent, start syncing
        Intent startIntent = getIntent();
        String action = startIntent.getAction();
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action) || SyncingService.isG4Connected(getApplicationContext())) {
            statusBarIcons.setUSB(true);
            log.debug("Starting syncing in OnCreate...");
            SyncingService.startActionSingleSync(mContext, SyncingService.MIN_SYNC_PAGES);
        } else {
            // reset the top icons to their default state
            statusBarIcons.setDefaults();
        }

        // Check (only once) to see if they have opted in to shared data for research
        if (!preferences.hasAskedForData()) {
            // Prompt user to ask to donate data to research
            AlertDialog.Builder dataDialog = new AlertDialog.Builder(this)
                    .setCancelable(false)
                    .setTitle(R.string.donate_dialog_title)
                    .setMessage(R.string.donate_dialog_summary)
                    .setPositiveButton(R.string.donate_dialog_yes, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            mTracker.send(new HitBuilders.EventBuilder("DataDonateQuery", "Yes").build());
                            preferences.setDataDonateEnabled(true);
                        }
                    })
                    .setNegativeButton(R.string.donate_dialog_no, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            mTracker.send(new HitBuilders.EventBuilder("DataDonateQuery", "No").build());
                            preferences.setDataDonateEnabled(true);
                        }
                    })
                    .setIcon(R.drawable.ic_launcher);

            dataDialog.show();

            preferences.setAskedForData(true);
        }

        // Report API vs mongo stats once per session
        reportUploadMethods(mTracker);
        pebble = new Pebble(getApplicationContext());
        pebble.setUnits(preferences.getPreferredUnits());
        pebble.setPwdName(preferences.getPwdName());

    }

    public void reportUploadMethods(Tracker tracker) {
        if (preferences.isRestApiEnabled()) {
            for (String url : preferences.getRestApiBaseUris()) {
                String apiVersion = (RestUriUtils.isV1Uri(URI.create(url))) ? "WebAPIv1" : "Legacy WebAPI";
                tracker.send(new HitBuilders.EventBuilder("Upload", apiVersion).build());
            }
        }
        if (preferences.isMongoUploadEnabled()) {
            tracker.send(new HitBuilders.EventBuilder("Upload", "Mongo").build());
        }
    }

    private void migrateToNewStyleRestUris() {
        List<String> newUris = Lists.newArrayList();
        for (String uriString : preferences.getRestApiBaseUris()) {
            if (uriString.contains("@http")) {
                List<String> splitUri = Splitter.on('@').splitToList(uriString);
                Uri oldUri = Uri.parse(splitUri.get(1));
                String newAuthority = Joiner.on('@').join(splitUri.get(0), oldUri.getEncodedAuthority());
                Uri newUri = oldUri.buildUpon().encodedAuthority(newAuthority).build();
                newUris.add(newUri.toString());
            } else {
                newUris.add(uriString);
            }
        }
        preferences.setRestApiBaseUris(newUris);
    }

    private void ensureSavedUrisAreValid() {
        if (PreferencesValidator.validateMongoUriSyntax(getApplicationContext(),
                preferences.getMongoClientUri()).isPresent()) {
            preferences.setMongoClientUri(null);
        }
        List<String> filteredRestUris = Lists.newArrayList();
        for (String uri : preferences.getRestApiBaseUris()) {
            if (!PreferencesValidator.validateRestApiUriSyntax(getApplicationContext(), uri).isPresent()) {
                filteredRestUris.add(uri);
            }
        }
        preferences.setRestApiBaseUris(filteredRestUris);
    }

    private void ensureIUnderstandDialogDisplayed() {
        if (!preferences.getIUnderstand()) {
            // Prompt user to ask to donate data to research
            AlertDialog.Builder dataDialog = new AlertDialog.Builder(this)
                    .setCancelable(false)
                    .setTitle(R.string.pref_title_i_understand)
                    .setMessage(R.string.pref_summary_i_understand)
                    .setPositiveButton(R.string.donate_dialog_yes, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            preferences.setIUnderstand(true);
                        }
                    })
                    .setNegativeButton(R.string.donate_dialog_no, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            android.os.Process.killProcess(android.os.Process.myPid());
                            System.exit(1);
                        }
                    })
                    .setIcon(R.drawable.ic_launcher);
            dataDialog.show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        log.debug("onPaused called.");
        mWebView.pauseTimers();
        mWebView.onPause();
        mHandler.removeCallbacks(updateTimeAgo);
    }

    public void setPebble(Pebble pebble) {
        this.pebble = pebble;
    }

    @Override
    protected void onResume() {
        super.onResume();
        log.debug("onResumed called.");
        mWebView.onResume();
        mWebView.resumeTimers();

        // Set and deal with mmol/L<->mg/dL conversions
        log.debug("display_options_units: " + preferences.getPreferredUnits().name());
        pebble.config(preferences.getPwdName(), preferences.getPreferredUnits());
        int sgv = (Integer) mTextSGV.getTag(R.string.display_sgv);

        int direction = (Integer) mTextSGV.getTag(R.string.display_trend);
        if (sgv != -1) {
            GlucoseReading sgvReading = new GlucoseReading(sgv, GlucoseUnits.MGDL);
            mTextSGV.setText(getSGVStringByUnit(sgvReading, TrendArrow.values()[direction]));
        }

        mWebView.loadUrl("javascript:updateUnits(" + Boolean.toString(preferences.getPreferredUnits() == GlucoseUnits.MMOL) + ")");

        mHandler.post(updateTimeAgo);
        // FIXME: (klee) need to find a better way to do this. Too many things are hooking in here.
        if (statusBarIcons != null) {
            statusBarIcons.checkForRootOptionChanged();
        }
    }

    private String getSGVStringByUnit(GlucoseReading sgv, TrendArrow trend) {
        String sgvStr = sgv.asStr(preferences.getPreferredUnits());
        return (sgv.asMgdl() != -1) ?
                (isSpecialValue(sgv)) ?
                        getEGVSpecialValue(sgv).get().toString() : sgvStr + " " + trend.symbol() : "---";
    }

    @Override
    protected void onDestroy() {
        log.debug("onDestroy called.");
        super.onDestroy();
        unregisterReceiver(mCGMStatusReceiver);
        unregisterReceiver(mDeviceStatusReceiver);
        unregisterReceiver(toastReceiver);
    }

    @Override
    protected void onStop() {
        super.onStop();
        GoogleAnalytics.getInstance(this).reportActivityStop(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        GoogleAnalytics.getInstance(this).reportActivityStart(this);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        mWebView.saveState(outState);
        outState.putString("saveJSONData", mJSONData);
        outState.putString("saveTextSGV", mTextSGV.getText().toString());
        outState.putString("saveTextTimestamp", mTextTimestamp.getText().toString());
        outState.putBoolean("saveImageViewUSB", statusBarIcons.getUSB());
        outState.putBoolean("saveImageViewUpload", statusBarIcons.getUpload());
        outState.putBoolean("saveImageViewTimeIndicator", statusBarIcons.getTimeIndicator());
        outState.putInt("saveImageViewBatteryIndicator", statusBarIcons.getBatteryIndicator());
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        // Restore the state of the WebView
        mWebView.restoreState(savedInstanceState);
        mJSONData = savedInstanceState.getString("mJSONData");
        mTextSGV.setText(savedInstanceState.getString("saveTextSGV"));
        mTextTimestamp.setText(savedInstanceState.getString("saveTextTimestamp"));
        statusBarIcons.setUSB(savedInstanceState.getBoolean("saveImageViewUSB"));
        statusBarIcons.setUpload(savedInstanceState.getBoolean("saveImageViewUpload"));
        statusBarIcons.setTimeIndicator(savedInstanceState.getBoolean("saveImageViewTimeIndicator"));
        statusBarIcons.setBatteryIndicator(savedInstanceState.getInt("saveImageViewBatteryIndicator"));
    }

    public class CGMStatusReceiver extends BroadcastReceiver {
        public static final String PROCESS_RESPONSE = "com.mSyncingServiceIntent.action.PROCESS_RESPONSE";

        @Override
        public void onReceive(Context context, Intent intent) {
            // Get response messages from broadcast
            int responseSGV = intent.getIntExtra(SyncingService.RESPONSE_SGV, -1);
            GlucoseReading reading = new GlucoseReading(responseSGV, GlucoseUnits.MGDL);
            TrendArrow trend = TrendArrow.values()[intent.getIntExtra(SyncingService.RESPONSE_TREND, 0)];
            long responseSGVTimestamp = intent.getLongExtra(SyncingService.RESPONSE_TIMESTAMP, -1L);
            boolean responseUploadStatus = intent.getBooleanExtra(SyncingService.RESPONSE_UPLOAD_STATUS, false);
            long responseNextUploadTime = intent.getLongExtra(SyncingService.RESPONSE_NEXT_UPLOAD_TIME, -1);
            long responseDisplayTime = intent.getLongExtra(SyncingService.RESPONSE_DISPLAY_TIME, new Date().getTime());
            lastRecordTime = responseSGVTimestamp;
            receiverOffsetFromUploader = new Date().getTime() - responseDisplayTime;
            int rcvrBat = intent.getIntExtra(SyncingService.RESPONSE_BAT, -1);
            String json = intent.getStringExtra(SyncingService.RESPONSE_JSON);

            if (responseSGV != -1) {
                pebble.sendDownload(reading, trend, responseSGVTimestamp);
            }
            // Reload d3 chart with new data
            if (json != null) {
                mJSONData = json;
                mWebView.loadUrl("javascript:updateData(" + mJSONData + ")");
            }

            // Update icons
            statusBarIcons.setUpload(responseUploadStatus);

            // Update UI with latest record information
            mTextSGV.setText(getSGVStringByUnit(reading, trend));
            mTextSGV.setTag(R.string.display_sgv, reading.asMgdl());
            mTextSGV.setTag(R.string.display_trend, trend.getID());

            String timeAgoStr = "---";
            log.debug("Date: " + new Date().getTime());
            log.debug("Response SGV Timestamp: " + responseSGVTimestamp);
            if (responseSGVTimestamp > 0) {
                timeAgoStr = Utils.getTimeString(new Date().getTime() - responseSGVTimestamp);
            }

            mTextTimestamp.setText(timeAgoStr);
            mTextTimestamp.setTag(timeAgoStr);

            long nextUploadTime = standardMinutes(5).getMillis();

            if (responseNextUploadTime > nextUploadTime) {
                log.debug("Receiver's time is less than current record time, possible time change.");
                mTracker.send(new HitBuilders.EventBuilder("Main", "Time change").build());
            } else if (responseNextUploadTime > 0) {
                log.debug("Setting next upload time to {}", responseNextUploadTime);
                nextUploadTime = responseNextUploadTime;
            } else {
                log.debug("OUT OF RANGE: Setting next upload time to {} ms.", nextUploadTime);
            }

            if (Minutes.minutesBetween(new DateTime(), new DateTime(responseDisplayTime))
                    .isGreaterThan(Minutes.minutes(20))) {
                log.warn("Receiver time is off by 20 minutes or more.");
                mTracker.send(new HitBuilders.EventBuilder("Main", "Time difference > 20 minutes").build());
                statusBarIcons.setTimeIndicator(false);
            } else {
                statusBarIcons.setTimeIndicator(true);
            }

            statusBarIcons.setBatteryIndicator(rcvrBat);

            mHandler.removeCallbacks(syncCGM);
            mHandler.postDelayed(syncCGM, nextUploadTime);
            // Start updating the timeago only if the screen is on
            PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
            if (pm.isScreenOn())
                mHandler.postDelayed(updateTimeAgo, nextUploadTime / 5);
        }
    }

    BroadcastReceiver mDeviceStatusReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            switch (action) {
                case UsbManager.ACTION_USB_DEVICE_DETACHED:
                    statusBarIcons.setDefaults();
                    mHandler.removeCallbacks(syncCGM);
                    break;
                case UsbManager.ACTION_USB_DEVICE_ATTACHED:
                    statusBarIcons.setUSB(true);
                    log.debug("Starting syncing on USB attached...");
                    SyncingService.startActionSingleSync(mContext, SyncingService.MIN_SYNC_PAGES);
                    break;
                case Intent.ACTION_BATTERY_CHANGED:
                    batLevel = intent.getIntExtra("level", 0);
                    break;
            }
        }
    };

    // Runnable to start service as needed to sync with mCGMStatusReceiver and cloud
    public Runnable syncCGM = new Runnable() {
        public void run() {
            SyncingService.startActionSingleSync(mContext, SyncingService.MIN_SYNC_PAGES);
        }
    };

    //FIXME: Strongly suggest refactoring this
    public Runnable updateTimeAgo = new Runnable() {
        @Override
        public void run() {
            long delta = new Date().getTime() - lastRecordTime + receiverOffsetFromUploader;
            if (lastRecordTime == 0) delta = 0;

            String timeAgoStr;
            if (lastRecordTime == -1) {
                timeAgoStr = "---";
            } else if (delta < 0) {
                timeAgoStr = getString(R.string.TimeChangeDetected);
            } else {
                timeAgoStr = Utils.getTimeString(delta);
            }
            mTextTimestamp.setText(timeAgoStr);
            mHandler.removeCallbacks(updateTimeAgo);
            mHandler.postDelayed(updateTimeAgo, standardMinutes(1).getMillis());
        }
    };

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_settings) {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        } else if (id == R.id.feedback_settings) {
            reporter.reportFeedback();
        } else if (id == R.id.gap_sync) {
            SyncingService.startActionSingleSync(getApplicationContext(), SyncingService.GAP_SYNC_PAGES);
        } else if (id == R.id.close_settings) {
            mHandler.removeCallbacks(syncCGM);
            finish();
        }

        return super.onOptionsItemSelected(item);
    }

    public static class StatusBarIcons extends Fragment {
        @InjectView(R.id.imageViewUSB) ImageView mImageViewUSB;
        @InjectView(R.id.imageViewUploadStatus) ImageView mImageViewUpload;
        @InjectView(R.id.imageViewTimeIndicator) ImageView mImageViewTimeIndicator;
        @InjectView(R.id.imageViewRcvrBattery) ImageView mImageRcvrBattery;
        @InjectView(R.id.rcvrBatteryLabel) TextView mRcvrBatteryLabel;

        private boolean usbActive;
        private boolean uploadActive;
        private boolean displayTimeSync;
        private int batteryLevel;

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View view = inflater.inflate(R.layout.fragment_icon_status, container, false);
            ButterKnife.inject(this, view);
            setDefaults();
            return view;
        }

        public void checkForRootOptionChanged() {
            if (!PreferenceManager.getDefaultSharedPreferences(
                    getActivity()).getBoolean(PreferenceKeys.ROOT_ENABLED, false)) {
                mImageRcvrBattery.setVisibility(View.GONE);
                mRcvrBatteryLabel.setVisibility(View.GONE);
            } else {
                mImageRcvrBattery.setVisibility(View.VISIBLE);
                mRcvrBatteryLabel.setVisibility(View.VISIBLE);
            }
        }

        public void setDefaults() {
            setUSB(false);
            setUpload(false);
            setTimeIndicator(false);
            setBatteryIndicator(0);
        }

        public void setUSB(boolean active) {
            usbActive = active;
            if (active) {
                mImageViewUSB.setImageResource(R.drawable.ic_usb_connected);
                mImageViewUSB.setTag(R.drawable.ic_usb_connected);
            } else {
                mImageViewUSB.setImageResource(R.drawable.ic_usb_disconnected);
                mImageViewUSB.setTag(R.drawable.ic_usb_disconnected);
            }
        }

        public void setUpload(boolean active) {
            uploadActive = active;
            if (active) {
                mImageViewUpload.setImageResource(R.drawable.ic_upload_success);
                mImageViewUpload.setTag(R.drawable.ic_upload_success);
            } else {
                mImageViewUpload.setImageResource(R.drawable.ic_upload_fail);
                mImageViewUpload.setTag(R.drawable.ic_upload_fail);
            }
        }

        public void setTimeIndicator(boolean active) {
            displayTimeSync = active;
            if (active) {
                mImageViewTimeIndicator.setImageResource(R.drawable.ic_clock_good);
                mImageViewTimeIndicator.setTag(R.drawable.ic_clock_good);
            } else {
                mImageViewTimeIndicator.setImageResource(R.drawable.ic_clock_bad);
                mImageViewTimeIndicator.setTag(R.drawable.ic_clock_bad);
            }
        }

        public void setBatteryIndicator(int batLvl) {
            batteryLevel = batLvl;
            mImageRcvrBattery.setImageLevel(batteryLevel);
            mImageRcvrBattery.setTag(batteryLevel);
        }

        public boolean getUSB() {
            return usbActive;
        }

        public boolean getUpload() {
            return uploadActive;
        }

        public boolean getTimeIndicator() {
            return displayTimeSync;
        }

        public int getBatteryIndicator() {
            if (mImageRcvrBattery == null) {
                return 0;
            }
            return (Integer) mImageRcvrBattery.getTag();
        }
    }
}