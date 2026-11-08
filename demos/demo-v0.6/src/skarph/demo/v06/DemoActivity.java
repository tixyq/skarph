// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.demo.v06;

import android.app.Activity;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;

import skarph.android.EngineView;
import skarph.core.Engine;

public final class DemoActivity extends Activity {
    private EngineView view;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        Engine engine = new Engine();
        engine.setScreen(new MenuDemoScreen());
        view = new EngineView(this, engine);
        setContentView(view);
    }

    protected void onPause() {
        super.onPause();
        view.onPause();
    }

    protected void onResume() {
        super.onResume();
        view.onResume();
    }

    protected void onDestroy() {
        view.destroy(); // releases SoundPool / MediaPlayer
        super.onDestroy();
    }
}
