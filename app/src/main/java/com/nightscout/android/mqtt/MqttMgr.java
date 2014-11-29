package com.nightscout.android.mqtt;

import android.util.Log;
import com.google.common.collect.Lists;
import com.nightscout.core.mqtt.*;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.List;

//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;


public class MqttMgr implements MqttCallback, MqttMgrObservable, MqttPingerObserver, MqttTimerObserver {
//    protected static final Logger log = LoggerFactory.getLogger(MqttMgr.class);
    protected final String TAG = MqttMgr.class.getSimpleName();

    private List<MqttMgrObserver> observers = Lists.newArrayList();
    private String mDeviceId;
    //private MqttDefaultFilePersistence mDataStore;
    private MemoryPersistence mDataStore;
    private MqttConnectOptions mOpts;
    private MqttClient mClient;

    private String user = null;
    private String pass = null;
    private String mqttUrl = null;
    private String[] mqTopics = null;
    private String lastWill = null;
    private String deviceIDStr = null;
    protected boolean initialCallbackSetup = false;
    protected MqttConnectionState state;
    protected MqttPinger pinger;
    protected MqttTimer timer;


    public MqttMgr(String user, String pass, String deviceIDStr) {
        super();
        this.user = user;
        this.pass = pass;
        this.deviceIDStr = deviceIDStr;
        mDeviceId = String.format(Constants.DEVICE_ID_FORMAT,deviceIDStr);
        state = MqttConnectionState.DISCONNECTED;
    }

    public void connect(String url, MqttPinger keepAlive){
        connect(url,lastWill, keepAlive, timer);
    }

    public void connect(String url, MqttPinger keepAlive, MqttTimer timer){
        connect(url, lastWill ,keepAlive, timer);
    }

    public void connect(){
        connect(mqttUrl, lastWill, pinger, timer);
    }

    public void setPinger(MqttPinger pinger) {
        this.pinger = pinger;
    }

    public void setTimer(MqttTimer timer) {
        this.timer = timer;
    }

    public void connect(String url, String lwt, MqttPinger keepAlive, MqttTimer reconnectTimer) {
        state = MqttConnectionState.CONNECTING;
        if (user == null || pass == null) {
            Log.e(TAG, "User and/or password is null. Please verify arguments to the constructor");
            return;
        }
        setupOpts(lwt);
        // Save the URL for later re-connections
        if (mqttUrl == null) {
            mqttUrl = url;
        }
        mDataStore = new MemoryPersistence();
        try {
            Log.d(TAG, "Connecting to URL: " + mqttUrl);
            mClient = new MqttClient(mqttUrl, mDeviceId, mDataStore);
            mClient.connect(mOpts);
            if (pinger == null) {
                pinger = keepAlive;
                pinger.setKeepAliveInterval(Constants.KEEPALIVE_INTERVAL);
            }
            if (timer == null) {
                timer = reconnectTimer;
            }
            pinger.setMqttClient(mClient);
            pinger.start();
            pinger.registerObserver(this);
            timer.activate();
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

    public void subscribe(String... topics){
        mqTopics = topics;
        Log.d(TAG, "Number of topics to subscribe to: " + mqTopics.length);
        for (String topic: mqTopics){
            try {
                Log.d(TAG, "Subscribing to " + topic);
                mClient.subscribe(topic, 2);
            } catch (MqttException e) {
                Log.e(TAG, "Unable to subscribe to topic " + topic, e);
            }
        }
        Log.d(TAG, "Verifying callback setup");
        if (! initialCallbackSetup) {
            Log.d(TAG, "Setting up callback");
            mClient.setCallback(MqttMgr.this);
            Log.d(TAG, "Set up callback");
            initialCallbackSetup = false;
        }
        Log.d(TAG, "Finished verifying callback setup");
    }

    public void publish(String message,String topic){
        publish(message.getBytes(), topic);
    }

    public void publish(byte[] message, String topic){
        Log.d(TAG, "Publishing " + message + " to " + topic);
        try {
            mClient.publish(topic,message,1,true);
        } catch (MqttException e) {
            Log.w(TAG, "Unable to publish message to " + topic);
            reconnectDelayed();
        }
    }

    public void registerObserver(MqttMgrObserver observer) {
        observers.add(observer);
        Log.d(TAG, "Number of registered observers: " + observers.size());
    }

    public void unregisterObserver(MqttMgrObserver observer) {
        observers.remove(observer);
    }

    public void notifyObservers(String topic, MqttMessage message) {
        for (MqttMgrObserver observer:observers){
            Log.d(TAG, "Calling back to registered users");
            try {
                observer.onMessage(topic, message);
            } catch (Exception e){
                // Horrible catch all but I don't want the manager to die and reconnect
                Log.e(TAG, "Caught an exception: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void notifyDisconnect() {
        Log.d(TAG, "In notifyDisconnect()");
    }

    public boolean isConnected(){
        return state == MqttConnectionState.CONNECTED;
    }

    @Override
    public void connectionLost(Throwable throwable) {
        if (state!= MqttConnectionState.CONNECTED) {
            Log.d(TAG, "Current state is not connected so ignoring this since we don't care");
            return;
        }
        notifyDisconnect();
        Log.w(TAG, "The connection was lost");
        if (mqttUrl==null || mqTopics==null){
            Log.e(TAG, "Somehow lost the connection and mqttUrl and/or mqTopics have not been set. Make sure to use connect() and subscribe() methods of this class");
            return;
        }
        if (! mClient.isConnected()) {
            reconnectDelayed();
        } else {
            Log.w(TAG, "Received connection lost but mClient is reporting we're online?!");
        }
    }

    @Override
    public void messageArrived(String s, MqttMessage mqttMessage) throws Exception {
        byte[] message = mqttMessage.getPayload();
        Log.i(TAG, "  Topic:\t" + s +
                "  Message:\t" + new String(message) +
                "  QoS:\t" + mqttMessage.getQos());
        if (!mqttMessage.isDuplicate())
            notifyObservers(s,mqttMessage);
        else
            Log.i(TAG, "Possible duplicate message");
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {
        Log.i(TAG, "deliveryComplete called");
    }


    public void reconnectDelayed(){
        reconnectDelayed(Constants.RECONNECT_DELAY);
    }

    public void reconnectDelayed(long delayMs){
        Log.i(TAG, "Attempting to reconnect again in " + delayMs / 1000 + " seconds");
        timer.setTimer(delayMs);
    }

    @Override
    public boolean onFailedPing() {
        return reconnect();
    }

    public boolean reconnect(){
        if (pinger.isNetworkActive()) {
            state = MqttConnectionState.RECONNECTING;
            Log.d(TAG, "Reconnecting");
            close();
            mClient = null;
            connect();
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
        if (state== MqttConnectionState.DISCONNECTING || state == MqttConnectionState.DISCONNECTED)
            return;
        try {
            if (mClient!=null && mClient.isConnected()) {
                mClient.disconnect();
            }
        } catch (MqttException e) {
            Log.e(TAG, "Error disconnecting", e);
        }
        state = MqttConnectionState.DISCONNECTED;
    }

    public void close(){
        try {
            Log.i(TAG, "Attempting to close the MQTTMgr");
            if (state == MqttConnectionState.CONNECTED)
                disconnect();
            if (mClient!=null)
                mClient.close();
            mClient = null;
            timer.deactivate();
            pinger.stop();
        } catch (MqttException e) {
            Log.e(TAG, "Exception while closing connection", e);
        }
    }

    @Override
    public void timerUp() {
        Log.d(TAG, "Timer is up");
        reconnect();
    }
}