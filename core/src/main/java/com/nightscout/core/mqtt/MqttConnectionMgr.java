package com.nightscout.core.mqtt;

public interface MqttConnectionMgr {
    public void connect();
    public void disconnect();
    public void reconnect(long ms);
    public void reconnect();
    public void isOnline();
}
