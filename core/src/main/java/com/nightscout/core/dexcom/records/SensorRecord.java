package com.nightscout.core.dexcom.records;

import com.google.common.base.Optional;
import com.google.protobuf.InvalidProtocolBufferException;
import com.nightscout.core.protobuf.Download;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class SensorRecord extends GenericTimestampRecord {

    private long unfiltered;
    private long filtered;
    private int rssi;
    private int OFFSET_UNFILTERED = 8;
    private int OFFSET_FILTERED = 12;
    private int OFFSET_RSSI = 16;

    public SensorRecord(byte[] packet) {
        super(packet);
        unfiltered = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getInt(OFFSET_UNFILTERED);
        filtered = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getInt(OFFSET_FILTERED);
        rssi = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getShort(OFFSET_RSSI);
    }

    public SensorRecord(long unfiltered, long filtered, int rssi, long time){
        super(time);
        this.unfiltered = unfiltered;
        this.filtered = filtered;
        this.rssi = rssi;
    }

    public long getUnfiltered() {
        return unfiltered;
    }

    public long getFiltered() {
        return filtered;
    }

    public int getRssi() {
        return rssi;
    }

    @Override
    public Download.CookieMonsterG4Sensor toProtoBuf() {
        return Download.CookieMonsterG4Sensor.newBuilder()
                .setTimestamp(systemTimeSeconds)
                .setFiltered(filtered)
                .setUnfiltered(unfiltered)
                .setRssi(rssi)
                .build();
    }

    @Override
    public Optional<SensorRecord> fromProtoBuf(byte[] byteArray){
        try {
            Download.CookieMonsterG4Sensor record = Download.CookieMonsterG4Sensor.parseFrom(byteArray);
            return Optional.of(new SensorRecord(record.getUnfiltered(), record.getFiltered(), record.getRssi(), record.getTimestamp()));
        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
        }
        return Optional.absent();
    }
}
