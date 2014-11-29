package com.nightscout.core.dexcom.records;

import com.google.common.base.Optional;
import com.google.protobuf.InvalidProtocolBufferException;
import com.nightscout.core.dexcom.Constants;
import com.nightscout.core.protobuf.Download;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Date;

public class EGVRecord extends GenericTimestampRecord {

    private int bGValue;
    private Constants.TREND_ARROW_VALUES trend;

    public EGVRecord(byte[] packet) {
        // system_time (UInt), display_time (UInt), glucose (UShort), trend_arrow (Byte), crc (UShort))
        super(packet);
        int eGValue = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getShort(8);
        bGValue = eGValue & Constants.EGV_VALUE_MASK;
        int trendValue = ByteBuffer.wrap(packet).get(10) & Constants.EGV_TREND_ARROW_MASK;
        trend = Constants.TREND_ARROW_VALUES.values()[trendValue];
    }

    public EGVRecord(int bGValue, Constants.TREND_ARROW_VALUES trend, Date displayTime, Date systemTime){
        super(displayTime, systemTime);
        this.bGValue=bGValue;
        this.trend=trend;
    }

    public EGVRecord(int bGValue, Download.Direction trend, long systemTime){
        super(systemTime);
    }

    public int getBGValue() {
        return bGValue;
    }

    public Constants.TREND_ARROW_VALUES getTrend() {
        return trend;
    }

    public JSONObject toJSON() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("sgv", getBGValue());
            obj.put("date", getDisplayTimeSeconds());
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return obj;
    }

    public Download.CookieMonsterG4EGV toProtoBuf() {
        return Download.CookieMonsterG4EGV.newBuilder()
                .setTimestamp(systemTimeSeconds)
                .setDirection(trend.toProtobuf())
                .setSgv(bGValue).build();
    }

    public Optional<EGVRecord> fromProtoBuf(byte[] byteArray){
        try {
            Download.CookieMonsterG4EGV record = Download.CookieMonsterG4EGV.parseFrom(byteArray);
            return Optional.of(new EGVRecord(record.getSgv(),record.getDirection(),record.getTimestamp()));
        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
        }
        return Optional.absent();
    }
}
