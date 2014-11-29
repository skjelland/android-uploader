package com.nightscout.core.dexcom.records;

import com.google.common.base.Optional;
import com.google.protobuf.InvalidProtocolBufferException;
import com.nightscout.core.protobuf.Download;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class MeterRecord extends GenericTimestampRecord {

    private int meterBG;
    private int meterTime;

    public MeterRecord(byte[] packet) {
        super(packet);
        meterBG = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getShort(8);
        meterTime = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getInt(10);
    }

    public MeterRecord(int meterBG, int meterTime, long time){
        super(time);
        this.meterBG = meterBG;
        this.meterTime = meterTime;
        this.systemTimeSeconds = time;
    }

    public int getMeterBG() {
        return meterBG;
    }

    public int getMeterTime() {
        return meterTime;
    }

    @Override
    public Download.CookieMonsterG4Meter toProtoBuf() {
        return Download.CookieMonsterG4Meter.newBuilder()
                .setTimestamp(systemTimeSeconds)
                .setMeterBg(meterBG)
                .setMeterTime(meterTime)
                .build();
    }

    @Override
    public Optional<MeterRecord> fromProtoBuf(byte[] byteArray){
        try {
            Download.CookieMonsterG4Meter record = Download.CookieMonsterG4Meter.parseFrom(byteArray);
            return Optional.of(new MeterRecord(record.getMeterBg(), record.getMeterTime(), record.getTimestamp()));
        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
        }
        return Optional.absent();
    }
}
