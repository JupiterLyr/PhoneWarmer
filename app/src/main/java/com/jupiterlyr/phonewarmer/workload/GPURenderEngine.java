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

    private static final String TAG = "GPURenderEngine";

    /** GL 线程上的初始化结果回调，回调发生在 GL 线程。 */
    public interface ErrorListener {
        /** Shader 编译/链接失败时调用 */
        void onGlInitError(String message);
        /** GL 上下文创建并初始化完成时调用（默认空实现，便于兼容） */
        default void onGlReady() {}
    }

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean glReady = new AtomicBoolean(false);
    private static volatile float gpuLoad = 0.0f;
    private long frameCount = 0;
    private long fpsWindowStart = 0;

    private volatile ErrorListener errorListener;

    // 着色器程序
    private int program;
    // viewport 尺寸（像素），由 onSurfaceChanged 提供给片段着色器做 uv 归一化
    private int viewportWidth = 1;
    private int viewportHeight = 1;

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
        try {
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            compileShaders();
            glReady.set(true);
            android.util.Log.i(TAG, "GL surface created, shaders ready");
            ErrorListener l = errorListener;
            if (l != null) {
                try {
                    l.onGlReady();
                } catch (Exception ignored) {
                }
            }
        } catch (RuntimeException e) {
            // 在 GL 线程吞掉异常，避免直接崩溃 GL 线程；通过回调通知上层
            glReady.set(false);
            running.set(false);
            android.util.Log.e(TAG, "GL init failed: " + e.getMessage(), e);
            ErrorListener l = errorListener;
            if (l != null) {
                try {
                    l.onGlInitError(e.getMessage());
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        viewportWidth = Math.max(1, width);
        viewportHeight = Math.max(1, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (!glReady.get()) return;

        boolean isRunning = running.get();

        // 待机/运行都进入正常绘制流程，区别只在每帧 draw call 次数上：
        // - 运行时：50 次 draw 制造高负载
        // - 待机时：1 次 draw，仅用于显示一个低负载的待机动画，避免窗口纯黑
        long currentTime = System.currentTimeMillis();
        if (fpsWindowStart == 0) {
            fpsWindowStart = currentTime;
        }
        frameCount++;

        long elapsed = currentTime - fpsWindowStart;
        if (elapsed >= 1000) {
            if (isRunning) {
                float fps = frameCount * 1000.0f / elapsed;
                // 60fps * 50draw = 3000 draw/s 视为满负载
                float load = Math.min(fps * 50.0f / 30.0f, 98.0f);
                gpuLoad = load;
            } else {
                // 待机时不上报负载
                gpuLoad = 0.0f;
            }
            fpsWindowStart = currentTime;
            frameCount = 0;
        } else if (isRunning && gpuLoad <= 0.0f && frameCount >= 3) {
            // 启动初期还未达到 1s，给一个非零的初值让 UI 立刻显示"运行中"
            gpuLoad = 1.0f;
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
        float time = (System.currentTimeMillis() % 100000L) / 1000.0f;
        GLES20.glUniform1f(timeHandle, time);

        // 设置 viewport 分辨率 uniform，让片段着色器据此归一化 uv
        int resolutionHandle = GLES20.glGetUniformLocation(program, "resolution");
        if (resolutionHandle >= 0) {
            GLES20.glUniform2f(resolutionHandle, (float) viewportWidth, (float) viewportHeight);
        }

        // 待机时通过 uniform 让片段着色器降低亮度，给出柔和的暗色待机画面
        int idleHandle = GLES20.glGetUniformLocation(program, "idle");
        if (idleHandle >= 0) {
            GLES20.glUniform1f(idleHandle, isRunning ? 0.0f : 1.0f);
        }

        // 运行时绘制 50 次以制造负载；待机时仅 1 次保证画面有内容
        int drawCalls = isRunning ? 50 : 1;
        for (int i = 0; i < drawCalls; i++) {
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        GLES20.glDisableVertexAttribArray(positionHandle);
    }

    private void compileShaders() {
        // 顶点着色器：直接铺满全屏四边形，不再扰动位置（之前的扰动会导致 4 顶点
        // 错位，TRIANGLE_STRIP 无法覆盖整个 viewport，外侧露出 clear color 的黑底）
        String vertexShaderCode =
                "attribute vec4 vPosition;" +
                        "void main() {" +
                        "  gl_Position = vPosition;" +
                        "}";

        // 片段着色器：使用 resolution 归一化 uv，叠加多频率正弦/余弦做彩色波纹
        // 注意：所有 uniform 与片段默认精度都是 mediump，避免链接精度不匹配。
        // GLSL 字符串靠 + 拼接时不会插入换行，因此 GLSL 内部不要写 // 行注释，
        // 否则会把后面所有代码都注释掉，导致 main 函数为空、像素全为黑色。
        String fragmentShaderCode =
                "precision mediump float;" +
                        "uniform mediump float time;" +
                        "uniform mediump vec2 resolution;" +
                        "uniform mediump float idle;" +
                        "void main() {" +
                        "  vec2 uv = gl_FragCoord.xy / resolution;" +
                        "  vec2 p = uv * 2.0 - 1.0;" +
                        "  float d = length(p);" +
                        "  float r = 0.5 + 0.5 * sin(time * 1.3 + p.x * 4.0 + d * 6.0);" +
                        "  float g = 0.5 + 0.5 * sin(time * 1.7 + p.y * 4.0 + d * 5.0 + 2.0);" +
                        "  float b = 0.5 + 0.5 * sin(time * 2.1 + (p.x + p.y) * 5.0 + 4.0);" +
                        "  float stripe = 0.5 + 0.5 * sin((p.x + p.y) * 8.0 - time * 3.0);" +
                        "  vec3 color = vec3(r, g, b) * (0.7 + 0.3 * stripe);" +
                        // idle=1 时调暗到约 18% 亮度，并叠一层冷色调，作为待机画面
                        "  vec3 idleColor = vec3(0.05, 0.08, 0.12) + 0.13 * vec3(b, r, g);" +
                        "  vec3 finalColor = mix(color, idleColor, idle);" +
                        "  gl_FragColor = vec4(finalColor, 1.0);" +
                        "}";

        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode);

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
        // 停止后将 gpuLoad 归零，避免 UI 显示残留数值；frameCount/fpsWindowStart 由
        // onDrawFrame 在下一次进入时自然重置（待机模式仍会持续触发 onDrawFrame）
        gpuLoad = 0.0f;
    }

    public void setErrorListener(ErrorListener listener) {
        this.errorListener = listener;
    }

    public static float getGpuLoad() {
        return gpuLoad;
    }

    public boolean isRunning() {
        return running.get();
    }
}