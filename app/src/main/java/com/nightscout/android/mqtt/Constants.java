package com.nightscout.android.mqtt;

public class Constants {
    public static final int	MQTT_QOS_0 = 0; // QOS Level 0 ( Delivery Once no confirmation )
    public static final int MQTT_QOS_1 = 1; // QOS Level 1 ( Delevery at least Once with confirmation )
    public static final int	MQTT_QOS_2 = 2; // QOS Level 2 ( Delivery only once with confirmation with handshake )
    public static final long RECONNECT_DELAY=60000L;
    public static final int KEEPALIVE_INTERVAL=150000;

    public static final String RECONNECT_INTENT_FILTER="com.nightscout.android.MQTT_RECONNECT";
    public static final String KEEPALIVE_INTENT_FILTER="com.nightscout.android.MQTT_KEEPALIVE";
}
