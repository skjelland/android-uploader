package com.nightscout.core.download;

import com.nightscout.core.dexcom.records.CalRecord;
import com.nightscout.core.dexcom.records.EGVRecord;
import com.nightscout.core.dexcom.records.MeterRecord;
import com.nightscout.core.dexcom.records.SensorRecord;
import org.joda.time.DateTime;

import java.util.List;

public class G4DownloadBuilder {
    private DateTime downloadTimestamp;
    private DownloadStatus downloadStatus;
    private int uploaderBattery;
    private List<EGVRecord> egvRecords;
    private List<MeterRecord> meterRecords;
    private List<CalRecord> calRecords;
    private List<SensorRecord> sensorRecords;
    private int receiverBattery;
    private GlucoseUnits units;
    private long sysTime;
    private long dispTime;

    public G4DownloadBuilder setDownloadTimestamp(DateTime downloadTimestamp) {
        this.downloadTimestamp = downloadTimestamp;
        return this;
    }

    public G4DownloadBuilder setDownloadStatus(DownloadStatus downloadStatus) {
        this.downloadStatus = downloadStatus;
        return this;
    }

    public G4DownloadBuilder setUploaderBattery(int uploaderBattery) {
        this.uploaderBattery = uploaderBattery;
        return this;
    }

    public G4DownloadBuilder setEgvRecords(List<EGVRecord> egvRecords) {
        this.egvRecords = egvRecords;
        return this;
    }

    public G4DownloadBuilder setMeterRecords(List<MeterRecord> meterRecords) {
        this.meterRecords = meterRecords;
        return this;
    }

    public G4DownloadBuilder setCalRecords(List<CalRecord> calRecords) {
        this.calRecords = calRecords;
        return this;
    }

    public G4DownloadBuilder setSensorRecords(List<SensorRecord> sensorRecords) {
        this.sensorRecords = sensorRecords;
        return this;
    }

    public G4DownloadBuilder setReceiverBattery(int receiverBattery) {
        this.receiverBattery = receiverBattery;
        return this;
    }

    public G4DownloadBuilder setUnits(GlucoseUnits units) {
        this.units = units;
        return this;
    }

    public G4DownloadBuilder setSysTime(long sysTime) {
        this.sysTime = sysTime;
        return this;
    }

    public G4DownloadBuilder setDispTime(long dispTime) {
        this.dispTime = dispTime;
        return this;
    }

    public G4Download createG4Download() {
        return new G4Download(downloadTimestamp, downloadStatus, uploaderBattery, egvRecords, meterRecords, calRecords, sensorRecords, receiverBattery, units, sysTime, dispTime);
    }
}