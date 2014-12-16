package com.nightscout.android.exceptions;

import android.app.Application;

import com.nightscout.android.R;

import org.acra.ACRA;
import org.acra.ACRAConfiguration;
import org.acra.ACRAConfigurationException;
import org.acra.ErrorReporter;
import org.acra.ReportingInteractionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.TimeZone;

public class AcraReporter implements Reporter {
    private static final Logger logger = LoggerFactory.getLogger(AcraReporter.class);
    private ErrorReporter errorReporter;
    private final Application application;

    public AcraReporter(Application app) {
        this.application = app;
    }

    @Override
    public void initialize() {
        ACRA.init(application);
        errorReporter = ACRA.getErrorReporter();
        errorReporter.putCustomData("timezone", TimeZone.getDefault().getID());
    }

    @Override
    public void reportFeedback() {
        ACRAConfiguration acraConfiguration = ACRA.getConfig();
        // Set to dialog to get user comments
        try {
            acraConfiguration.setMode(ReportingInteractionMode.DIALOG);
            acraConfiguration.setResToastText(0);
        } catch (ACRAConfigurationException e) {
            logger.error(e.getMessage());
        }
        errorReporter.handleException(null);
        // Reset back to toast
        try {
            acraConfiguration.setResToastText(R.string.crash_toast_text);
            acraConfiguration.setMode(ReportingInteractionMode.TOAST);
        } catch (ACRAConfigurationException e) {
            logger.error(e.getMessage());
        }
    }
}
