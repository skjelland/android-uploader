package com.nightscout.android.exceptions;

import android.app.Application;
import android.widget.Toast;

public class StubbedReporter implements Reporter {
    private final Application application;

    public StubbedReporter(Application app) {
        this.application = app;
    }

    @Override
    public void initialize() { }

    @Override
    public void reportFeedback() {
        // TODO(trhodeos): make this actually a dialog.
        Toast.makeText(application, "Debug mode: stubbed feedback report.", Toast.LENGTH_LONG)
                .show();
    }
}
