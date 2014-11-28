package com.nightscout.android.mqtt;

import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import com.google.common.collect.Lists;
import com.nightscout.core.mqtt.Constants;
import com.nightscout.core.mqtt.MqttConnectionState;
import com.nightscout.core.mqtt.MqttMgrObservable;
import com.nightscout.core.mqtt.MqttMgrObserver;
import com.nightscout.core.mqtt.MqttPinger;
import com.nightscout.core.mqtt.MqttPingerObserver;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.util.List;


public class MqttMgr implements MqttCallback, MqttMgrObservable, MqttPingerObserver {
    private static final String TAG = MqttMgr.class.getSimpleName();

    private List<MqttMgrObserver> observers = Lists.newArrayList();

    private ReconnectReceiver reconnectReceiver;

    private String mDeviceId;
    //private MqttDefaultFilePersistence mDataStore;
    private MemoryPersistence mDataStore;
    private MqttConnectOptions mOpts;
    private MqttClient mClient;

    private Context context;
    private String user=null;
    private String pass = null;
    private String mqttUrl = null;
    private String[] mqTopics = null;
    private String lastWill = null;
    private String deviceIDStr =null;
    private AlarmManager alarmMgr;
    private Intent reconnectIntent;
    private PendingIntent reconnectPendingIntent;
    protected boolean initialCallbackSetup = false;
    protected MqttConnectionState state;
    protected MqttPinger pinger;


    public MqttMgr(Context context, String user, String pass, String deviceIDStr) {
        super();
        this.context = context;
        this.user = user;
        this.pass = pass;
        this.deviceIDStr = deviceIDStr;
        mDeviceId = String.format(Constants.DEVICE_ID_FORMAT,deviceIDStr);
        alarmMgr = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        state = MqttConnectionState.DISCONNECTED;
    }

    public void connect(String url, MqttPinger keepAlive){
        connect(url,null, keepAlive);
    }

    public void setPinger(MqttPinger pinger) {
        this.pinger = pinger;
    }

    public void connect(String url, String lwt, MqttPinger keepAlive) {
        state= MqttConnectionState.CONNECTING;
        if (user == null || pass == null) {
            Log.e(TAG, "User and/or password is null. Please verify arguments to the constructor");
            return;
        }
        setupOpts(lwt);
        // Save the URL for later re-connections
        mqttUrl = url;
        mDataStore = new MemoryPersistence();
        try {
            Log.d(TAG, "Connecting to URL: " + url);
            mClient = new MqttClient(url, mDeviceId, mDataStore);
            mClient.connect(mOpts);
            pinger = keepAlive;
            pinger.setKeepAliveInterval(Constants.KEEPALIVE_INTERVAL);
            pinger.setMqttClient(mClient);
            pinger.start();
            pinger.registerObserver(this);
            state = MqttConnectionState.CONNECTED;
        } catch (MqttException | IllegalArgumentException e) {
            Log.e(TAG, "Error while connecting: ", e);
        }
    }

    private void setupOpts(String lwt){
        if (lwt != null)
            lastWill = lwt;
        mOpts = new MqttConnectOptions();
        mOpts.setUserName(user);
        mOpts.setPassword(pass.toCharArray());
        mOpts.setKeepAliveInterval(Constants.KEEPALIVE_INTERVAL);
        Log.d(TAG, "Current keepalive is: " + mOpts.getKeepAliveInterval());
        if (lastWill != null) {
            mOpts.setWill("/uploader", lastWill.getBytes(), 2, true);
        }
        mOpts.setCleanSession(Constants.MQTT_CLEAN_SESSION);
    }

    private void setupReconnect(){
        reconnectReceiver = new ReconnectReceiver();
        reconnectIntent = new Intent(Constants.RECONNECT_INTENT_FILTER);
        reconnectIntent.putExtra("device",deviceIDStr);
        reconnectPendingIntent = PendingIntent.getBroadcast(context, 61, reconnectIntent, 0);

        context.registerReceiver(reconnectReceiver, new IntentFilter(Constants.RECONNECT_INTENT_FILTER));
    }

    public void subscribe(String... topics){
        mqTopics = topics;
        Log.d(TAG,"Number of topics to subscribe to: "+mqTopics.length);
        for (String topic: mqTopics){
            try {
                Log.d(TAG, "Subscribing to " + topic);
                mClient.subscribe(topic, 2);
            } catch (MqttException e) {
                Log.e(TAG, "Unable to subscribe to topic "+topic,e);
            }
        }
        Log.d(TAG,"Verifying callback setup");
        if (! initialCallbackSetup) {
            Log.d(TAG,"Setting up callback");
            mClient.setCallback(MqttMgr.this);
            Log.d(TAG,"Set up callback");
            initialCallbackSetup = false;
        }
        Log.d(TAG, "Finished verifying callback setup");
    }

    public void publish(String message,String topic){
        publish(message.getBytes(), topic);
    }

    public void publish(byte[] message, String topic){
        try {
            Log.d(TAG,"Publishing "+new String(message,"UTF-8")+" to "+ topic);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        try {
            mClient.publish(topic,message,1,true);
        } catch (MqttException e) {
            Log.wtf(TAG,"Unable to publish message: "+message+" to "+topic);
            reconnectDelayed();
        }
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return (cm.getActiveNetworkInfo() != null &&
                cm.getActiveNetworkInfo().isAvailable() &&
                cm.getActiveNetworkInfo().isConnected());
    }

    public void registerObserver(MqttMgrObserver observer) {
        observers.add(observer);
        Log.d(TAG,"Number of registered observers: "+observers.size());
    }

    public void unregisterObserver(MqttMgrObserver observer) {
        observers.remove(observer);
    }

    public void notifyObservers(String topic, MqttMessage message) {
        for (MqttMgrObserver observer:observers){
            Log.v(TAG,"Calling back to registered users");
            try {
                observer.onMessage(topic, message);
            } catch (Exception e){
                // Horrible catch all but I don't want the manager to die and reconnect
                Log.e(TAG,"Caught an exception: "+e.getMessage(),e);
            }
        }
    }

    @Override
    public void notifyDisconnect() {
        Log.d(TAG,"In notifyDisconnect()");
    }

    protected boolean isConnected(){
        return state== MqttConnectionState.CONNECTED;
    }

    @Override
    public void connectionLost(Throwable throwable) {
        if (state!= MqttConnectionState.CONNECTED) {
            Log.d(TAG,"Current state is not connected so ignoring this since we don't care");
            return;
        }
        notifyDisconnect();
        Log.w(TAG,"The connection was lost");
        if (mqttUrl==null || mqTopics==null){
            Log.e(TAG,"Somehow lost the connection and mqttUrl and/or mqTopics have not been set. Make sure to use connect() and subscribe() methods of this class");
            return;
        }
        if (! mClient.isConnected()) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemoteCGM "+deviceIDStr);
            wl.acquire();
            reconnectDelayed();
            wl.release();
        } else {
            Log.wtf(TAG, "Received connection lost but mClient is reporting we're online?!");
        }
    }

    @Override
    public void messageArrived(String s, MqttMessage mqttMessage) throws Exception {
        byte[] message = mqttMessage.getPayload();
        Log.i(TAG,"  Topic:\t" + s +
                "  Message:\t" + new String(message) +
                "  QoS:\t" + mqttMessage.getQos());
        if (!mqttMessage.isDuplicate())
            notifyObservers(s,mqttMessage);
        else
            Log.i(TAG, "Possible duplicate message");
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {
        Log.i(TAG,"deliveryComplete called");
    }


    public void reconnectDelayed(){
        reconnectDelayed(Constants.RECONNECT_DELAY);
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    public void reconnectDelayed(long delay_ms){
        Log.i(TAG, "Attempting to reconnect again in "+delay_ms/1000+" seconds");
//        reconnectReceiver = new ReconnectReceiver();
        reconnectIntent = new Intent(Constants.RECONNECT_INTENT_FILTER);
        reconnectIntent.putExtra("device",deviceIDStr);
        reconnectPendingIntent = PendingIntent.getBroadcast(context, 61, reconnectIntent, 0);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
            alarmMgr.setExact(AlarmManager.RTC_WAKEUP,new Date().getTime()+delay_ms,reconnectPendingIntent);
        else
            alarmMgr.set(AlarmManager.RTC_WAKEUP,new Date().getTime()+delay_ms,reconnectPendingIntent);
    }

    @Override
    public boolean onFailedPing() {
        return reconnect();
    }

    public boolean reconnect(){
        if (isOnline()) {
            state = MqttConnectionState.RECONNECTING;
            Log.d(TAG, "Reconnecting");
            alarmMgr.cancel(reconnectPendingIntent);
            close();
            mClient = null;
//            connect(mqttUrl);
            // No need to subscribe to anything if we don't have anything to subscribe to.
            if (mqTopics!=null)
                subscribe(mqTopics);
        } else {
            Log.d(TAG, "Reconnect requested but I was not online");
        }
        // TODO: fixme!
        return true;
    }

    public void disconnect(){
        if (state== MqttConnectionState.DISCONNECTING || state== MqttConnectionState.DISCONNECTED)
            return;
        try {
            if (mClient!=null && mClient.isConnected()) {
                mClient.disconnect();
            }
        } catch (MqttException e) {
            Log.e(TAG,"Error disconnecting",e);
        }
        state= MqttConnectionState.DISCONNECTING;
        if (context != null && reconnectReceiver != null) {
            context.unregisterReceiver(reconnectReceiver);
            reconnectReceiver = null;
        }
        alarmMgr.cancel(reconnectPendingIntent);
        pinger.stop();
        state = MqttConnectionState.DISCONNECTED;
    }

    public void close(){
        try {
            Log.i(TAG,"Attempting to close the MQTTMgr");
            if (state== MqttConnectionState.CONNECTED)
                disconnect();
            if (mClient!=null)
                mClient.close();
            mClient = null;
        } catch (MqttException e) {
            Log.e(TAG,"Exception while closing connection",e);
        }
    }

    protected class ReconnectReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG,"Received a broadcast: "+intent.getAction());
            if (intent.getAction().equals(Constants.RECONNECT_INTENT_FILTER)) {
                Log.d(TAG,"Received broadcast to reconnect");
                // Prevent a reconnect if we haven't subscribed to anything.
                // I suspect this was a race condition where a reconnect somehow triggered before the initial connection completed
                reconnect();
            }
        }
    }

    // TODO honor disable background data setting..
}