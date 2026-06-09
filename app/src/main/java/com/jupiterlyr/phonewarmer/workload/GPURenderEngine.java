package com.jupiterlyr.phonewarmer.workload;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class GPURenderEngine implements GLSurfaceView.Renderer {

    private final AtomicBoolean running = new AtomicBoolean(false);
    private static volatile float gpuLoad = 0.0f;
    private long frameCount = 0;
    private long lastFrameTime = 0;

    // 着色器程序
    private int program;
    private int vertexShader;
    private int fragmentShader;

    // 顶点数据
    private FloatBuffer vertexBuffer;
    private final float[] vertices = {
            -1.0f, -1.0f, 0.0f,
            1.0f, -1.0f, 0.0f,
            -1.0f, 1.0f, 0.0f,
            1.0f, 1.0f, 0.0f
    };

    public GPURenderEngine() {
        // 初始化顶点缓冲区
        ByteBuffer bb = ByteBuffer.allocateDirect(vertices.length * 4);
        bb.order(ByteOrder.nativeOrder());
        vertexBuffer = bb.asFloatBuffer();
        vertexBuffer.put(vertices);
        vertexBuffer.position(0);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // 初始化OpenGL
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        compileShaders();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (!running.get()) return;

        long currentTime = System.currentTimeMillis();
        if (lastFrameTime == 0) {
            lastFrameTime = currentTime;
        }

        frameCount++;

        // 计算GPU负载（基于帧率和渲染复杂度）
        if (frameCount % 30 == 0) {
            long elapsed = currentTime - lastFrameTime;
            if (elapsed > 0) {
                float fps = 30000.0f / elapsed; // 30帧的时间
                // 基于帧率计算基础负载
                float baseLoad = Math.min(fps / 60.0f * 70.0f, 65.0f);
                // 增加渲染复杂度带来的负载
                float complexityLoad = 25.0f; // 50次绘制的复杂度负载
                gpuLoad = Math.min(baseLoad + complexityLoad, 98.0f);
                lastFrameTime = currentTime;
            }
        }

        // 清屏
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // 使用着色器程序
        GLES20.glUseProgram(program);

        // 设置顶点属性
        int positionHandle = GLES20.glGetAttribLocation(program, "vPosition");
        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer);

        // 设置时间uniform
        int timeHandle = GLES20.glGetUniformLocation(program, "time");
        float time = (System.currentTimeMillis() % 10000) / 1000.0f;
        GLES20.glUniform1f(timeHandle, time);

        // 绘制复杂图形
        for (int i = 0; i < 50; i++) {
            // 多次绘制增加GPU负载
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        GLES20.glDisableVertexAttribArray(positionHandle);
    }

    private void compileShaders() {
        // 顶点着色器 - 复杂的数学运算
        String vertexShaderCode =
                "attribute vec4 vPosition;" +
                        "uniform float time;" +
                        "void main() {" +
                        "  vec4 pos = vPosition;" +
                        "  pos.x += sin(time + vPosition.x * 10.0) * 0.1;" +
                        "  pos.y += cos(time + vPosition.y * 10.0) * 0.1;" +
                        "  gl_Position = pos;" +
                        "}";
//        String vertexShaderCode =
//                "attribute vec4 vPosition;" +
//                        "void main() {" +
//                        "  gl_Position = vPosition;" +
//                        "}";

        // 片段着色器 - 复杂的像素计算
        String fragmentShaderCode =
                "precision mediump float;" +
                        "uniform float time;" +
                        "void main() {" +
                        "  vec2 uv = gl_FragCoord.xy / 800.0;" +
                        "  float r = sin(uv.x * 25.0 + time) * 0.5 + 0.5;" +
                        "  r += sin(uv.y * 18.0 + time * 1.3) * 0.3;" +
                        "  r += sin((uv.x + uv.y) * 40.0 + time * 2.0) * 0.2;" +
                        "  " +
                        "  float g = cos(uv.y * 22.0 + time * 1.7) * 0.5 + 0.5;" +
                        "  g += cos(uv.x * 14.0 + time * 0.8) * 0.3;" +
                        "  g += cos((uv.x - uv.y) * 35.0 + time * 2.5) * 0.2;" +
                        "  " +
                        "  float b = sin((uv.x + uv.y) * 30.0 + time * 2.2) * 0.5 + 0.5;" +
                        "  b += cos(uv.x * uv.y * 50.0 + time * 3.0) * 0.3;" +
                        "  b += sin(uv.x * uv.y * 60.0 + time * 1.5) * 0.2;" +
                        "  " +
                        "  float noise = sin(uv.x * 150.0) * cos(uv.y * 150.0) * 0.1;" +
                        "  r += noise; g += noise; b += noise;" +
                        "  " +
                        "  gl_FragColor = vec4(r, g, b, 1.0);" +
                        "}";
//        String fragmentShaderCode =
//                "precision mediump float;" +
//                        "uniform float time;" +
//                        "void main() {" +
//                        "  float r = abs(sin(time));" +
//                        "  float g = abs(cos(time));" +
//                        "  float b = abs(sin(time * 0.5));" +
//                        "  gl_FragColor = vec4(r, g, b, 1.0);" +
//                        "}";

        vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode);
        fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode);

        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);

        // 检查链接状态
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            String error = GLES20.glGetProgramInfoLog(program);
            android.util.Log.e("GPURenderEngine", "Program linking failed: " + error);
            GLES20.glDeleteProgram(program);
            throw new RuntimeException("Program linking failed: " + error);
        }
    }

    private int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);

        // 检查编译状态
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            // 获取错误信息
            String error = GLES20.glGetShaderInfoLog(shader);
            android.util.Log.e("GPURenderEngine", "Shader compilation failed: " + error);
            android.util.Log.e("GPURenderEngine", "Shader code: " + shaderCode);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("Shader compilation failed: " + error);
        }

        return shader;
    }

    public void start() {
        running.set(true);
    }

    public void stop() {
        running.set(false);
    }

    public static float getGpuLoad() {
        return gpuLoad;
    }

    public boolean isRunning() {
        return running.get();
    }
}