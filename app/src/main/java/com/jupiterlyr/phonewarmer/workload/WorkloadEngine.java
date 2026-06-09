package com.jupiterlyr.phonewarmer.workload;

import android.opengl.GLSurfaceView;

import com.jupiterlyr.phonewarmer.monitor.SystemMonitor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WorkloadEngine {

    private final List<Worker> workers = new ArrayList<>();
    private ExecutorService executorService;
    private volatile boolean running = false;

    private GPURenderEngine gpuRenderer;
    private GLSurfaceView glSurfaceView;
    private SystemMonitor systemMonitor;
    private float currentGpuLoad = 0.0f;

    public synchronized void start(int intensity) {
        stop();

        int workerCount = Math.max(1, Math.min(intensity, 6));
        executorService = Executors.newFixedThreadPool(workerCount);
        running = true;

        for (int i = 0; i < workerCount; i++) {
            Worker worker = new Worker();
            workers.add(worker);
            executorService.execute(worker);
        }

        if (intensity >= 2 && glSurfaceView != null && gpuRenderer != null) {
            gpuRenderer.start();  // 启动GPU运算（如果可用）
        }
    }

    public synchronized void stop() {
        running = false;

        for (Worker worker : workers) {
            worker.stop();
        }
        workers.clear();

        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }

        if (gpuRenderer != null) {
            gpuRenderer.stop();
        }
    }

    public void setGLSurfaceView(GLSurfaceView surfaceView) {
        this.glSurfaceView = surfaceView;
        if (surfaceView != null) {
            // 在GL线程中安全地设置渲染器
            surfaceView.setEGLContextClientVersion(2); // 明确指定OpenGL ES 2.0
            this.gpuRenderer = new GPURenderEngine();
            surfaceView.setRenderer(gpuRenderer);
            surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY); // 开始时设置为按需渲染
        }
    }

    public void setSystemMonitor(SystemMonitor monitor) {
        this.systemMonitor = monitor;
    }

    public float getCurrentGpuLoad() {
        if (gpuRenderer != null && gpuRenderer.isRunning()) {
            return gpuRenderer.getGpuLoad();
        }
        return 0.0f;
    }

    public boolean isRunning() { return running; }

    private static class Worker implements Runnable {
        private volatile boolean active = true;

        @Override
        public void run() {
            // 初始化复杂的数据结构
            double[] array = new double[10000];
            for (int i = 0; i < array.length; i++) {
                array[i] = Math.random();
            }

            double seed1 = 123.456;
            double seed2 = 789.012;
            double seed3 = 3456.789;

            while (active && !Thread.currentThread().isInterrupted()) {
                // 多层嵌套循环增加计算复杂度
                for (int outer = 0; outer < 10000; outer++) {
                    // 内存密集型操作：频繁访问数组
                    for (int i = 0; i < array.length - 1; i++) {
                        array[i] = array[i] * array[i + 1] + Math.sin(array[i]);
                    }
                    // 复杂数学运算组合
                    for (int inner = 0; inner < 500; inner++) {
                        seed1 = Math.sin(seed1) * Math.cos(seed2) + Math.tan(seed3);
                        seed2 = Math.log(Math.abs(seed1) + 1) * Math.exp(seed2 / 100.0);
                        seed3 = Math.sqrt(Math.abs(seed1 * seed2)) + Math.pow(seed3, 1.001);
                        // 增加条件分支和复杂逻辑
                        if (inner % 50 == 0) {
                            double temp = seed1 + seed2 + seed3;
                            seed1 = Math.sin(temp) * Math.cos(temp);
                            seed2 = Math.cos(temp) * Math.sin(temp);
                            seed3 = Math.tan(temp) * Math.atan(temp);
                        }
                        // 防止数值溢出
                        if (Double.isNaN(seed1) || Double.isInfinite(seed1)) {
                            seed1 = 123.456;
                        }
                        if (Double.isNaN(seed2) || Double.isInfinite(seed2)) {
                            seed2 = 789.012;
                        }
                        if (Double.isNaN(seed3) || Double.isInfinite(seed3)) {
                            seed3 = 3456.789;
                        }
                    }
                    // 矩阵运算模拟
                    for (int i = 0; i < array.length / 2; i++) {
                        double sum = 0;
                        for (int j = 0; j < 10; j++) {
                            sum += array[(i + j) % array.length] * Math.sin(i + j);
                        }
                        array[i] = sum / 10.0 + Math.cos(array[i]);
                    }
                }
            }
        }

        public void stop() {
            active = false;
        }
    }
}
