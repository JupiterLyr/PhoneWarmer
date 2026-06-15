package com.jupiterlyr.phonewarmer.service;

import android.opengl.GLSurfaceView;

import androidx.annotation.Nullable;

import com.jupiterlyr.phonewarmer.workload.GPURenderEngine;
import com.jupiterlyr.phonewarmer.workload.WorkloadEngine;

/**
 * Process-local burn session shared by the activity UI and the foreground service.
 */
public final class BurnSession {

    private static final BurnSession INSTANCE = new BurnSession();

    private final WorkloadEngine workloadEngine = new WorkloadEngine();

    private boolean burning = false;
    private int intensity = 2;

    private BurnSession() {
    }

    public static BurnSession getInstance() {
        return INSTANCE;
    }

    public synchronized void bindPreviewSurface(@Nullable GLSurfaceView surfaceView,
                                                @Nullable GPURenderEngine.ErrorListener listener) {
        workloadEngine.setGLSurfaceView(surfaceView, listener);
    }

    public synchronized void start(int newIntensity) {
        intensity = newIntensity;
        workloadEngine.start(newIntensity);
        burning = true;
    }

    public synchronized void stop() {
        workloadEngine.stop();
        burning = false;
    }

    public synchronized boolean isBurning() {
        return burning;
    }

    public synchronized int getIntensity() {
        return intensity;
    }
}
