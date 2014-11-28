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
import java.util.Locale;

public class MqttMgr implements MqttCallback, MqttMgrObservable {
    private List<MqttMgrObserver> observers=Lists.newArrayList();

    private static final String TAG = MqttMgr.class.getSimpleName();
    private static final String MQTT_KEEP_ALIVE_TOPIC_FORMAT = "/users/%s/keepalive"; // Topic format for KeepAlives
    private static final byte[] MQTT_KEEP_ALIVE_MESSAGE = { 0 }; // Keep Alive message to send
    private static final int MQTT_KEEP_ALIVE_QOS = Constants.MQTT_QOS_0; // Default Keepalive QOS
    private static final boolean MQTT_CLEAN_SESSION = true;
    private static final String DEVICE_ID_FORMAT = "%s";
    private AlarmReceiver keepAliveReceiver;
    private ReconnectReceiver reconnectReceiver;
    private NetConnReceiver netConnReceiver;
    private String mDeviceId;
    //    private MqttDefaultFilePersistence mDataStore;
    private MemoryPersistence mDataStore;
    private MqttConnectOptions mOpts;
    private MqttClient mClient;

    private Context context;
    private String user=null;
    private String pass=null;
    private String mqttUrl=null;
    private String[] mqTopics=null;
    private String lastWill=null;
    private String deviceIDStr =null;
    private AlarmManager alarmMgr;
    private Intent reconnectIntent;
    private PendingIntent reconnectPendingIntent;
    private Intent keepAliveIntent;
    private PendingIntent keepAlivePendingIntent;
    //    protected boolean connected=false;
    protected boolean initialCallbackSetup=false;
    protected MqttConnectionState state;


    public MqttMgr(Context context, String user, String pass, String deviceIDStr) {
        super();
        this.context = context;
        this.user=user;
        this.pass=pass;
        this.deviceIDStr=deviceIDStr;
        mDeviceId = String.format(DEVICE_ID_FORMAT,deviceIDStr);
        alarmMgr = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        state= MqttConnectionState.DISCONNECTED;
    }

    public void connect(String url){
        connect(url,null);
    }

    //FIXME it is possible to call connect without first calling initConnect - thereby bypassing the setupKeepAlives - which limits our ability to detect a connection loss
    // Should be able to change the visiblity of the methods. Perhaps renaming as well?
    public void initConnect(String url){
        initConnect(url,null);
    }

    public void initConnect(String url, String lwt) {
        setupReconnect();
        setupKeepAlives();
        connect(url, lwt);
    }

    public void connect(String url, String lwt) {
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
            setNextKeepAlive();
            state= MqttConnectionState.CONNECTED;
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

//        if (lastWill != null) {
//            mOpts.setWill("/uploader", lastWill.getBytes(), 2, true);
//        }
        mOpts.setCleanSession(MQTT_CLEAN_SESSION);
    }

    private void setupKeepAlives(){
        Log.d(TAG, "Setting up keepalives");
        keepAliveReceiver =new AlarmReceiver();
        keepAliveIntent = new Intent(Constants.KEEPALIVE_INTENT_FILTER);
        keepAliveIntent.putExtra("device",deviceIDStr);
//        keepAlivePendingIntent=PendingIntent.getBroadcast(context, 61, keepAliveIntent, PendingIntent.FLAG_CANCEL_CURRENT);
        keepAlivePendingIntent=PendingIntent.getBroadcast(context, 71, keepAliveIntent, 0);
        context.registerReceiver(keepAliveReceiver, new IntentFilter(Constants.KEEPALIVE_INTENT_FILTER));
        // TODO - See if FLAG_NO_CREATE will stomp on other instances
    }

    private void setupReconnect(){
        reconnectReceiver=new ReconnectReceiver();
        reconnectIntent = new Intent(Constants.RECONNECT_INTENT_FILTER);
        reconnectIntent.putExtra("device",deviceIDStr);
        reconnectPendingIntent=PendingIntent.getBroadcast(context, 61, reconnectIntent, 0);

        context.registerReceiver(reconnectReceiver, new IntentFilter(Constants.RECONNECT_INTENT_FILTER));
    }

    private void setupNetworkNotifications(){
        netConnReceiver = new NetConnReceiver();
        context.registerReceiver(netConnReceiver,
                new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
    }

    public void subscribe(String... topics){
        mqTopics=topics;
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
            initialCallbackSetup=false;
        }
        Log.d(TAG,"Finished verifying callback setup");
    }

    public void publish(String message,String topic){
        publish(message.getBytes(),topic);
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

    @Override
    public void registerObserver(MqttMgrObserver observer) {
        observers.add(observer);
        Log.d(TAG,"Number of registered observers: "+observers.size());
    }

    @Override
    public void unregisterObserver(MqttMgrObserver observer) {
        observers.remove(observer);
    }

    @Override
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
        byte[] message=mqttMessage.getPayload();
        Log.i(TAG,"  Topic:\t" + s +
                "  Message:\t" + new String(message) +
                "  QoS:\t" + mqttMessage.getQos());
        if (!mqttMessage.isDuplicate())
            notifyObservers(s,mqttMessage);
        else
            Log.i(TAG, "Possible duplicate message");
        setNextKeepAlive();
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private void setNextKeepAlive() {
//        if (state!=State.CONNECTED)
//            return;
        Log.d(TAG,"Canceling previous alarm");
        alarmMgr.cancel(keepAlivePendingIntent);
        Log.d(TAG,"Setting next keep alive to trigger in "+(Constants.KEEPALIVE_INTERVAL-3000)/1000+" seconds");
        keepAliveIntent = new Intent(Constants.KEEPALIVE_INTENT_FILTER);
        keepAliveIntent.putExtra("device",deviceIDStr);
        keepAlivePendingIntent=PendingIntent.getBroadcast(context, 61, keepAliveIntent, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
            alarmMgr.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + Constants.KEEPALIVE_INTERVAL - 3000, keepAlivePendingIntent);
        else
            alarmMgr.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + Constants.KEEPALIVE_INTERVAL - 3000, keepAlivePendingIntent);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {
        Log.i(TAG,"deliveryComplete called");
        setNextKeepAlive();
    }

    public void sendKeepalive() {
        Log.d(TAG, "Sending keepalive to " + mqttUrl + " deviceID=>" + mDeviceId);
        MqttMessage message = new MqttMessage(MQTT_KEEP_ALIVE_MESSAGE);
        message.setQos(MQTT_KEEP_ALIVE_QOS);
        try {
            // The mKeepAliveTopic variable was hanging on to an older client handle that was stale and would trigger reconnects too frequently.
            // See if this helps clear things up - rather than nulling the mKeepAliveTopic everytime we lose our connection.
            mClient.publish(String.format(Locale.US, MQTT_KEEP_ALIVE_TOPIC_FORMAT, mDeviceId),message);
        } catch (MqttException e) {
            Log.wtf(TAG,"Exception during ping",e);
            Log.wtf(TAG,"Reason code:"+e.getReasonCode());
            notifyDisconnect();
            reconnectDelayed(5000);
        }
//        setNextKeepAlive();
    }

    public void reconnectDelayed(){
        reconnectDelayed(Constants.RECONNECT_DELAY);
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    public void reconnectDelayed(long delay_ms){
        Log.i(TAG, "Attempting to reconnect again in "+delay_ms/1000+" seconds");
//        reconnectReceiver=new ReconnectReceiver();
        reconnectIntent = new Intent(Constants.RECONNECT_INTENT_FILTER);
        reconnectIntent.putExtra("device",deviceIDStr);
        reconnectPendingIntent=PendingIntent.getBroadcast(context, 61, reconnectIntent, 0);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
            alarmMgr.setExact(AlarmManager.RTC_WAKEUP,new Date().getTime()+delay_ms,reconnectPendingIntent);
        else
            alarmMgr.set(AlarmManager.RTC_WAKEUP,new Date().getTime()+delay_ms,reconnectPendingIntent);
    }

    private void reconnect(){
        if (isOnline()) {
            state= MqttConnectionState.RECONNECTING;
            Log.d(TAG, "Reconnecting");
            alarmMgr.cancel(reconnectPendingIntent);
            close();
            mClient=null;
//            mKeepAliveTopic=null;
            connect(mqttUrl);
            // No need to subscribe to anything if we don't have anything to subscribe to.
            if (mqTopics!=null)
                subscribe(mqTopics);
        } else {
            Log.d(TAG, "Reconnect requested but I was not online");
        }
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
        if (context != null && netConnReceiver != null) {
            context.unregisterReceiver(netConnReceiver);
            netConnReceiver=null;
        }
        if (context != null && keepAliveReceiver != null) {
            context.unregisterReceiver(keepAliveReceiver);
            keepAliveReceiver=null;
        }

        if (context != null && reconnectReceiver != null) {
            context.unregisterReceiver(reconnectReceiver);
            reconnectReceiver=null;
        }
        alarmMgr.cancel(keepAlivePendingIntent);
        alarmMgr.cancel(reconnectPendingIntent);
//        notifyDisconnect();
        state= MqttConnectionState.DISCONNECTED;
    }

    public void close(){
        try {
            Log.i(TAG,"Attempting to close the MQTTMgr");
            if (state== MqttConnectionState.CONNECTED)
                disconnect();
            if (mClient!=null)
                mClient.close();
            mClient=null;
        } catch (MqttException e) {
            Log.e(TAG,"Exception while closing connection",e);
        }
    }

    protected class AlarmReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG,"Received a broadcast: "+intent.getAction());
            if (intent.getAction().equals(Constants.KEEPALIVE_INTENT_FILTER)){
                if (intent.getExtras().get("device").equals(deviceIDStr)) {
                    Log.d(TAG, "Received a request to perform an MQTT keepalive operation on " + intent.getExtras().get("device"));
                    sendKeepalive();
                }else{
                    Log.d(TAG,deviceIDStr+": Ignored a request for "+intent.getExtras().get("device")+" to perform an MQTT keepalive operation");
                }
            }
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

    protected class NetConnReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG,"Received a broadcast: "+intent.getAction());
            if (intent.getAction().equals(ConnectivityManager.CONNECTIVITY_ACTION)){
                Log.d(TAG,"Received network broadcast "+intent.getAction());
                if (mqttUrl==null){
                    Log.e(TAG,"Ignoring connection change because mqttUrl is null");
                    return;
                }
                if (isOnline() && state== MqttConnectionState.CONNECTED) {
                    Log.i(TAG, "Network is online. Attempting to reconnect");
                    reconnectDelayed(5000);
                }
            }
        }
    }

    // TODO honor disable background data setting..
}