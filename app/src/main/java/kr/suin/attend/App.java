package kr.suin.attend;

import android.app.Application;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // keep a local copy so the list still shows (and taps queue up) when the internet drops
        try { Cloud.db().setPersistenceEnabled(true); } catch (Exception ignored) { }
    }
}
