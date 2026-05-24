#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdint>
#include <EGL/egl.h>
#include <GLES2/gl2.h>

#define LOG_TAG "IRazor_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static JavaVM *gJvm = nullptr;

struct GpuContext {
    EGLDisplay display;
    EGLContext context;
    EGLSurface surface;
    int width;
    int height;
    bool initialized;
};

static GpuContext gGpu = {};

static void initGpuContext() {
    if (gGpu.initialized) return;
    gGpu.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (gGpu.display == EGL_NO_DISPLAY) {
        LOGE("Failed to get EGL display");
        return;
    }
    EGLint major, minor;
    if (!eglInitialize(gGpu.display, &major, &minor)) {
        LOGE("Failed to initialize EGL");
        return;
    }
    EGLint attribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_BLUE_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_RED_SIZE, 8,
        EGL_DEPTH_SIZE, 16,
        EGL_NONE
    };
    EGLConfig config;
    EGLint numConfigs;
    if (!eglChooseConfig(gGpu.display, attribs, &config, 1, &numConfigs)) {
        LOGE("Failed to choose EGL config");
        return;
    }
    EGLint ctxAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    gGpu.context = eglCreateContext(gGpu.display, config, EGL_NO_CONTEXT, ctxAttribs);
    if (gGpu.context == EGL_NO_CONTEXT) {
        LOGE("Failed to create EGL context");
        return;
    }
    EGLint pbAttribs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
    gGpu.surface = eglCreatePbufferSurface(gGpu.display, config, pbAttribs);
    if (gGpu.surface == EGL_NO_SURFACE) {
        LOGE("Failed to create PBuffer surface");
        return;
    }
    if (!eglMakeCurrent(gGpu.display, gGpu.surface, gGpu.surface, gGpu.context)) {
        LOGE("Failed to make EGL context current");
        return;
    }
    gGpu.initialized = true;
    LOGI("GPU context initialized via EGL/GLES2");
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    gJvm = vm;
    LOGI("IRazor native library loaded (JNI_OnLoad)");
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_irazor_ai_MainActivity_nativeInitEngine(JNIEnv *env, jobject) {
    initGpuContext();
    LOGI("Native engine initialized, GPU ready: %d", gGpu.initialized);
    return gGpu.initialized ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_irazor_ai_MainActivity_nativeProcessBuffer(
    JNIEnv *env, jobject,
    jbyteArray buffer,
    jstring extension,
    jint flags
) {
    if (buffer == nullptr) return nullptr;

    jsize len = env->GetArrayLength(buffer);
    jbyte *raw = env->GetByteArrayElements(buffer, nullptr);
    if (raw == nullptr) return nullptr;

    const char *ext = (extension != nullptr)
        ? env->GetStringUTFChars(extension, nullptr)
        : ".bin";

    LOGI("nativeProcessBuffer: %d bytes, ext=%s, flags=%d", len, ext, flags);

    uint32_t hash = 0;
    for (jsize i = 0; i < len; i++) {
        hash = ((hash << 5) + hash) + static_cast<uint8_t>(raw[i]);
    }

    env->ReleaseByteArrayElements(buffer, raw, JNI_ABORT);
    if (extension != nullptr) env->ReleaseStringUTFChars(extension, ext);

    char result[128];
    snprintf(result, sizeof(result),
        "{\"status\":\"ok\",\"bytes\":%d,\"hash\":\"%08x\",\"engine\":\"irazor_native_v1\"}",
        len, hash);

    return env->NewStringUTF(result);
}

JNIEXPORT void JNICALL
Java_com_irazor_ai_MainActivity_nativeRenderFrame(JNIEnv *, jobject) {
    if (!gGpu.initialized) return;
    glClearColor(0.02f, 0.02f, 0.06f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    glFlush();
}


JNIEXPORT jbyteArray JNICALL
Java_com_irazor_ai_MainActivity_nativeDispatchCompute(
    JNIEnv *env, jobject,
    jbyteArray input,
    jint operation
) {
    if (input == nullptr) return nullptr;
    jsize len = env->GetArrayLength(input);
    jbyte *raw = env->GetByteArrayElements(input, nullptr);
    if (raw == nullptr) return nullptr;

    jbyteArray result = env->NewByteArray(len);
    jbyte *out = env->GetByteArrayElements(result, nullptr);

    for (jsize i = 0; i < len; i++) {
        switch (operation) {
            case 1: out[i] = ~raw[i]; break;                       
            case 2: out[i] = raw[i] ^ 0xFF; break;                 
            case 3: out[i] = raw[i] + static_cast<jbyte>(1); break; 
            default: out[i] = raw[i]; break;                       
        }
    }

    env->ReleaseByteArrayElements(result, out, 0);
    env->ReleaseByteArrayElements(input, raw, JNI_ABORT);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_irazor_ai_MainActivity_nativeGetGpuInfo(JNIEnv *env, jobject) {
    const char *renderer = reinterpret_cast<const char *>(glGetString(GL_RENDERER));
    const char *vendor   = reinterpret_cast<const char *>(glGetString(GL_VENDOR));
    const char *version  = reinterpret_cast<const char *>(glGetString(GL_VERSION));

    if (!renderer) renderer = "unknown";
    if (!vendor) vendor = "unknown";
    if (!version) version = "unknown";

    char info[512];
    snprintf(info, sizeof(info),
        "{\"renderer\":\"%s\",\"vendor\":\"%s\",\"version\":\"%s\",\"gpuReady\":%s}",
        renderer, vendor, version, gGpu.initialized ? "true" : "false");

    return env->NewStringUTF(info);
}

} 
