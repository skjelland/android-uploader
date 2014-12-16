package com.nightscout.android.modules;

import android.app.Application;

import com.nightscout.android.MainActivity;
import com.nightscout.android.Nightscout;
import com.nightscout.android.exceptions.AcraReporter;
import com.nightscout.android.exceptions.Reporter;
import com.nightscout.android.preferences.PreferencesModule;
import com.nightscout.android.ui.UiModule;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

@Module(
        includes = {
                PreferencesModule.class,
                UiModule.class
        },
        injects = {
                Nightscout.class,
                MainActivity.class
        }
)
public class NightscoutModule {
    private Application app;

    public NightscoutModule(Application app) {
        this.app = app;
    }

    @Provides @Singleton Application providesApplication() {
        return app;
    }

    @Provides @Singleton
    Reporter providesReporter(Application app) {
        return new AcraReporter(app);
    }
}
