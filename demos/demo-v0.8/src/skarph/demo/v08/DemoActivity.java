// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.demo.v08;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import skarph.android.AndroidGc;
import skarph.android.AndroidStorage;
import skarph.android.EngineView;
import skarph.core.Engine;
import skarph.core.Settings;

/**
 * Hosts the demo and can restart the graphics with another backend: the engine and the view are created
 * again (all GPU resources belong to one renderer). The chosen backend is saved. If the OpenGL ES 2.0 shaders
 * fail on a device, the demo falls back to ES 1.x by itself and remembers that.
 */
public final class DemoActivity extends Activity implements BackendSwitcher, EngineView.BackendListener {
    private static final String TAG = "skarph";
    private static final String PREF_FILE = "backend08.txt";

    private EngineView view;
    private boolean resumed;

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        Settings prefs = new Settings(4);
        prefs.load(new AndroidStorage(this), PREF_FILE);
        startEngine(prefs.getInt("backend", EngineView.BACKEND_AUTO));
    }

    private void startEngine(int backend) {
        if (view != null) {
            view.onPause();
            view.destroy();
        }
        Engine engine = new Engine();
        engine.setScreen(new StressDemoScreen(this));
        EngineView v = new EngineView(this, engine, backend);
        v.setBackendListener(this);
        view = v;
        setContentView(v);
        if (resumed) {
            v.onResume();
        }
    }

    private void saveBackend(int backend) {
        Settings prefs = new Settings(4);
        prefs.putInt("backend", backend);
        prefs.save(new AndroidStorage(this), PREF_FILE);
    }

    public int getGcCount() {
        return AndroidGc.count();
    }

    public boolean isGles2Available() {
        return EngineView.isGles2Supported(this);
    }

    /** Called on the GL thread by the demo screen. */
    public void switchBackend(final int backend) {
        runOnUiThread(new Runnable() {
            public void run() {
                saveBackend(backend);
                startEngine(backend);
            }
        });
    }

    /** Called on the UI thread when the ES 2.0 shaders do not work on this device. */
    public void onBackendFailed(EngineView failed, String reason) {
        if (failed != view) {
            return; // an old view that was already replaced
        }
        Log.e(TAG, "OpenGL ES 2.0 failed, using ES 1.x: " + reason);
        Toast.makeText(this, "OpenGL ES 2.0 failed, switching to ES 1.x", Toast.LENGTH_LONG).show();
        saveBackend(EngineView.BACKEND_GLES1);
        startEngine(EngineView.BACKEND_GLES1);
    }

    protected void onPause() {
        super.onPause();
        resumed = false;
        view.onPause();
    }

    protected void onResume() {
        super.onResume();
        resumed = true;
        view.onResume();
    }

    protected void onDestroy() {
        view.destroy();
        super.onDestroy();
    }
}
