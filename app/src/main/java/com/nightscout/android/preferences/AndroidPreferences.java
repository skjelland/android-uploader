package com.nightscout.android.preferences;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import com.google.common.collect.Lists;
import com.nightscout.core.preferences.NightscoutPreferences;

import java.util.List;

public class AndroidPreferences implements NightscoutPreferences {
    private final SharedPreferences preferences;

    public AndroidPreferences(SharedPreferences preferences) {
        this.preferences = preferences;
    }
    @Override
    public boolean isRestApiEnabled() {
        return preferences.getBoolean(PreferenceKeys.API_UPLOADER_ENABLED, false);
    }

    @Override
    public List<String> getRestApiBaseUris() {
        return Lists.newArrayList(preferences.getString(PreferenceKeys.API_URIS, "").split(" "));
    }

    @Override
    public boolean isCalibrationUploadEnabled() {
        return preferences.getBoolean(PreferenceKeys.CAL_UPLOAD_ENABLED, false);
    }

    @Override
    public boolean isSensorUploadEnabled() {
        return preferences.getBoolean(PreferenceKeys.SENSOR_UPLOAD_ENABLED, false);
    }

    @Override
    public boolean isDataDonateEnabled() {
        return preferences.getBoolean(PreferenceKeys.DATA_DONATE, false);
    }

    @Override
    public boolean isMongoUploadEnabled() {
        return preferences.getBoolean(PreferenceKeys.MONGO_UPLOADER_ENABLED, false);
    }

    @Override
    public void setDataDonateEnabled(boolean toDonate) {
        preferences.edit().putBoolean(PreferenceKeys.DATA_DONATE, toDonate).apply();
    }

    @Override
    public String getMongoClientUri() {
        return preferences.getString(PreferenceKeys.MONGO_URI, "");
    }

    @Override
    public String getMongoCollection() {
        return preferences.getString(PreferenceKeys.MONGO_COLLECTION, "dexcom");
    }

    @Override
    public String getMongoDeviceStatusCollection() {
        return preferences.getString(PreferenceKeys.MONGO_DEVICE_STATUS_COLLECTION, "devicestatus");
    }

    @Override
    public boolean isMqttEnabled() {
        return preferences.getBoolean(PreferenceKeys.MQTT_ENABLED, false);
    }

    @Override
    public String getMqttEndpoint() {
        return preferences.getString(PreferenceKeys.MQTT_ENDPOINT, "");
    }

    @Override
    public String getMqttUser() {
        return preferences.getString(PreferenceKeys.MQTT_USER, "");
    }

    @Override
    public String getMqttPass() {
        return preferences.getString(PreferenceKeys.MQTT_PASS, "");
    }

    @SuppressLint("CommitPrefEdits")
    public void setLastEgvMqttUpload(long timestamp) {
        preferences.edit().putLong(PreferenceKeys.MQTT_LAST_EGV_TIME, timestamp).commit();
    }

    @SuppressLint("CommitPrefEdits")
    public void setLastSensorMqttUpload(long timestamp) {
        preferences.edit().putLong(PreferenceKeys.MQTT_LAST_SENSOR_TIME, timestamp).commit();
    }

    @SuppressLint("CommitPrefEdits")
    public void setLastCalMqttUpload(long timestamp) {
        preferences.edit().putLong(PreferenceKeys.MQTT_LAST_CAL_TIME, timestamp).commit();
    }

    @SuppressLint("CommitPrefEdits")
    public void setLastMeterMqttUpload(long timestamp) {
        preferences.edit().putLong(PreferenceKeys.MQTT_LAST_METER_TIME, timestamp).commit();
    }

    public long getLastEgvMqttUpload() {
        return preferences.getLong(PreferenceKeys.MQTT_LAST_EGV_TIME, 0);
    }

    public long getLastSensorMqttUpload() {
        return preferences.getLong(PreferenceKeys.MQTT_LAST_SENSOR_TIME, 0);
    }

    public long getLastCalMqttUpload() {
        return preferences.getLong(PreferenceKeys.MQTT_LAST_CAL_TIME, 0);
    }

    public long getLastMeterMqttUpload() {
        return preferences.getLong(PreferenceKeys.MQTT_LAST_METER_TIME, 0);
    }
}
