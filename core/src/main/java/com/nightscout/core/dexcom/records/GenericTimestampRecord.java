package com.nightscout.core.dexcom.records;

import com.google.common.base.Optional;
import com.nightscout.core.dexcom.Utils;
import com.nightscout.core.protobuf.Download;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Date;

abstract public class GenericTimestampRecord implements ProtobufRecord {

    protected final int OFFSET_SYS_TIME = 0;
    protected final int OFFSET_DISPLAY_TIME = 4;
    protected Date systemTime;
    protected long systemTimeSeconds;
    protected Date displayTime;

    public GenericTimestampRecord(byte[] packet) {
        systemTimeSeconds = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getInt(OFFSET_SYS_TIME);
        systemTime = Utils.receiverTimeToDate(systemTimeSeconds);
        int dt = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getInt(OFFSET_DISPLAY_TIME);
        displayTime = Utils.receiverTimeToDate(dt);
    }

    public GenericTimestampRecord(Date displayTime, Date systemTime){
        this.displayTime=displayTime;
        this.systemTime=systemTime;
    }

    public GenericTimestampRecord(long systemTime) {
        this.systemTimeSeconds = systemTime;
    }

    public Date getSystemTime() {
        return systemTime;
    }

    public long getSystemTimeSeconds() {
        return systemTimeSeconds;
    }

    public Date getDisplayTime() {
        return displayTime;
    }

    public long getDisplayTimeSeconds() {
        return displayTime.getTime();
    }
}
