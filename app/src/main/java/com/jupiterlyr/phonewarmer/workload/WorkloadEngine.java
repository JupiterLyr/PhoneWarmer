package com.jupiterlyr.phonewarmer.workload;

import android.opengl.GLSurfaceView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WorkloadEngine {

    private static final String TAG = "WorkloadEngine";

    private final List<Worker> workers = new ArrayList<>();
    private ExecutorService executorService;
    private volatile boolean running = false;

    private GPURenderEngine gpuRenderer;
    private GLSurfaceView glSurfaceView;

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
            gpuRenderer.start();
        }
        // RenderMode 依然保持为 CONTINUOUSLY（在 setGLSurfaceView 中设置），
        // GPURenderEngine.onDrawFrame 会根据 running 状态区分“待机低负载动画”与“高负载烤机”。
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
        // 不再切回 WHEN_DIRTY：保持持续渲染模式，让待机动画仍能表示“GPU 可用”。
        // 负载差异由 GPURenderEngine 内部根据 running 状态决定 draw call 次数。
    }

    /**
     * 绑定 GLSurfaceView 并初始化 GPU 渲染器。
     * 如果初始化失败，会抛出 RuntimeException，并保证内部状态被清理（不会留下半初始化的引用）。
     *
     * @param surfaceView 要绑定的 GLSurfaceView，可为 null 表示不使用 GPU
     * @param errorListener GL 线程上 shader 编译/链接错误的回调，可为 null
     */
    public void setGLSurfaceView(GLSurfaceView surfaceView, GPURenderEngine.ErrorListener errorListener) {
        // 切换前先清理之前可能持有的引用
        this.glSurfaceView = null;
        this.gpuRenderer = null;

        if (surfaceView == null) {
            return;
        }

        try {
            surfaceView.setEGLContextClientVersion(2); // 明确指定OpenGL ES 2.0
            // 显式指定 EGL config，避免部分设备默认 chooser 匹配失败导致 onSurfaceCreated 永不触发（黑屏）
            // 8/8/8/8 = RGBA, 16 位深度, 0 模板
            surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
            GPURenderEngine renderer = new GPURenderEngine();
            renderer.setErrorListener(errorListener);
            surfaceView.setRenderer(renderer);
            // 始终使用持续渲染模式：待机时让 GPURenderEngine 绘制轻量级背景动画（避免黑屏），
            // 烤机时才提高 draw call 数量。避免使用 RENDERMODE_WHEN_DIRTY 导致需手动 requestRender。
            surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
            // 走到这里才认为初始化成功
            this.gpuRenderer = renderer;
            this.glSurfaceView = surfaceView;
        } catch (RuntimeException e) {
            // 主线程能直接捕获到的通常是参数校验类异常（如重复 setRenderer），
            // 失败时确保不留下半初始化的引用，外层可据此禁用 GPU 功能。
            this.glSurfaceView = null;
            this.gpuRenderer = null;
            android.util.Log.e(TAG, "setGLSurfaceView failed: " + e.getMessage(), e);
            throw e;
        }
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
