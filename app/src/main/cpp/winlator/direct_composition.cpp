#include <android/data_space.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/rect.h>
#include <dlfcn.h>
#include <errno.h>
#include <jni.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>

#include <atomic>
#include <deque>

#define LOG_TAG "DirectComposition"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct ASurfaceControl;
struct ASurfaceTransaction;
struct ASurfaceTransactionStats;

constexpr int8_t kVisibilityHide = 0;
constexpr int8_t kVisibilityShow = 1;
constexpr int8_t kTransparencyOpaque = 2;
constexpr int kReleaseFenceTimeoutMs = 3000;
constexpr int kLayerZOrder = 1;

typedef void (*OnCompleteFn)(void* context, ASurfaceTransactionStats* stats);

typedef ASurfaceControl* (*FnCreateFromWindow)(ANativeWindow*, const char*);
typedef void (*FnScRelease)(ASurfaceControl*);
typedef ASurfaceTransaction* (*FnTxCreate)();
typedef void (*FnTxDelete)(ASurfaceTransaction*);
typedef void (*FnTxApply)(ASurfaceTransaction*);
typedef void (*FnTxReparent)(ASurfaceTransaction*, ASurfaceControl*, ASurfaceControl*);
typedef void (*FnTxSetVisibility)(ASurfaceTransaction*, ASurfaceControl*, int8_t);
typedef void (*FnTxSetZOrder)(ASurfaceTransaction*, ASurfaceControl*, int32_t);
typedef void (*FnTxSetBuffer)(ASurfaceTransaction*, ASurfaceControl*, AHardwareBuffer*, int);
typedef void (*FnTxSetGeometry)(ASurfaceTransaction*, ASurfaceControl*, const ARect*, const ARect*, int32_t);
typedef void (*FnTxSetPosition)(ASurfaceTransaction*, ASurfaceControl*, int32_t, int32_t);
typedef void (*FnTxSetScale)(ASurfaceTransaction*, ASurfaceControl*, float, float);
typedef void (*FnTxSetCrop)(ASurfaceTransaction*, ASurfaceControl*, const ARect*);
typedef void (*FnTxSetBufferTransform)(ASurfaceTransaction*, ASurfaceControl*, int32_t);
typedef void (*FnTxSetBufferDataSpace)(ASurfaceTransaction*, ASurfaceControl*, int);
typedef void (*FnTxSetBufferTransparency)(ASurfaceTransaction*, ASurfaceControl*, int8_t);
typedef void (*FnTxSetOnComplete)(ASurfaceTransaction*, void*, OnCompleteFn);
typedef void (*FnStatsGetControls)(ASurfaceTransactionStats*, ASurfaceControl***, size_t*);
typedef void (*FnStatsReleaseControls)(ASurfaceControl**);
typedef int (*FnStatsGetPreviousReleaseFence)(ASurfaceTransactionStats*, ASurfaceControl*);

struct Api {
    bool resolved = false;
    bool available = false;
    FnCreateFromWindow createFromWindow = nullptr;
    FnScRelease scRelease = nullptr;
    FnTxCreate txCreate = nullptr;
    FnTxDelete txDelete = nullptr;
    FnTxApply txApply = nullptr;
    FnTxReparent txReparent = nullptr;
    FnTxSetVisibility txSetVisibility = nullptr;
    FnTxSetZOrder txSetZOrder = nullptr;
    FnTxSetBuffer txSetBuffer = nullptr;
    FnTxSetGeometry txSetGeometry = nullptr;
    FnTxSetPosition txSetPosition = nullptr;
    FnTxSetScale txSetScale = nullptr;
    FnTxSetCrop txSetCrop = nullptr;
    FnTxSetBufferTransform txSetBufferTransform = nullptr;
    FnTxSetBufferDataSpace txSetBufferDataSpace = nullptr;
    FnTxSetBufferTransparency txSetBufferTransparency = nullptr;
    FnTxSetOnComplete txSetOnComplete = nullptr;
    FnStatsGetControls statsGetControls = nullptr;
    FnStatsReleaseControls statsReleaseControls = nullptr;
    FnStatsGetPreviousReleaseFence statsGetPreviousReleaseFence = nullptr;
};

pthread_mutex_t g_api_mutex = PTHREAD_MUTEX_INITIALIZER;
Api g_api;

template <typename T>
void resolve(void* lib, T& target, const char* name) {
    target = reinterpret_cast<T>(dlsym(lib, name));
}

const Api& api() {
    pthread_mutex_lock(&g_api_mutex);
    if (!g_api.resolved) {
        g_api.resolved = true;
        void* lib = dlopen("libandroid.so", RTLD_NOW);
        if (lib) {
            resolve(lib, g_api.createFromWindow, "ASurfaceControl_createFromWindow");
            resolve(lib, g_api.scRelease, "ASurfaceControl_release");
            resolve(lib, g_api.txCreate, "ASurfaceTransaction_create");
            resolve(lib, g_api.txDelete, "ASurfaceTransaction_delete");
            resolve(lib, g_api.txApply, "ASurfaceTransaction_apply");
            resolve(lib, g_api.txReparent, "ASurfaceTransaction_reparent");
            resolve(lib, g_api.txSetVisibility, "ASurfaceTransaction_setVisibility");
            resolve(lib, g_api.txSetZOrder, "ASurfaceTransaction_setZOrder");
            resolve(lib, g_api.txSetBuffer, "ASurfaceTransaction_setBuffer");
            resolve(lib, g_api.txSetGeometry, "ASurfaceTransaction_setGeometry");
            resolve(lib, g_api.txSetPosition, "ASurfaceTransaction_setPosition");
            resolve(lib, g_api.txSetScale, "ASurfaceTransaction_setScale");
            resolve(lib, g_api.txSetCrop, "ASurfaceTransaction_setCrop");
            resolve(lib, g_api.txSetBufferTransform, "ASurfaceTransaction_setBufferTransform");
            resolve(lib, g_api.txSetBufferDataSpace, "ASurfaceTransaction_setBufferDataSpace");
            resolve(lib, g_api.txSetBufferTransparency, "ASurfaceTransaction_setBufferTransparency");
            resolve(lib, g_api.txSetOnComplete, "ASurfaceTransaction_setOnComplete");
            resolve(lib, g_api.statsGetControls, "ASurfaceTransactionStats_getASurfaceControls");
            resolve(lib, g_api.statsReleaseControls, "ASurfaceTransactionStats_releaseASurfaceControls");
            resolve(lib, g_api.statsGetPreviousReleaseFence, "ASurfaceTransactionStats_getPreviousReleaseFenceFd");
        }
        bool modernGeometry = g_api.txSetPosition && g_api.txSetScale && g_api.txSetCrop && g_api.txSetBufferTransform;
        bool geometry = modernGeometry || g_api.txSetGeometry;
        g_api.available = g_api.createFromWindow && g_api.scRelease && g_api.txCreate && g_api.txDelete
                && g_api.txApply && g_api.txReparent && g_api.txSetVisibility && g_api.txSetZOrder
                && g_api.txSetBuffer && g_api.txSetOnComplete && g_api.statsGetControls
                && g_api.statsReleaseControls && g_api.statsGetPreviousReleaseFence && geometry;
        LOGI("ASurfaceControl support: %s (geometry=%s dataspace=%s transparency=%s)",
             g_api.available ? "yes" : "no",
             modernGeometry ? "crop/position/scale" : (g_api.txSetGeometry ? "setGeometry" : "none"),
             g_api.txSetBufferDataSpace ? "yes" : "no",
             g_api.txSetBufferTransparency ? "yes" : "no");
    }
    pthread_mutex_unlock(&g_api_mutex);
    return g_api;
}

struct Layer {
    ASurfaceControl* sc = nullptr;
    jobject javaRef = nullptr;
    uint64_t currentAhb = 0;
    std::atomic<int> pendingCallbacks{0};
    std::atomic<bool> destroyed{false};
};

struct TxContext {
    Layer* layer;
    int64_t token;
    uint64_t previousAhb;
    bool destroy;
};

struct Event {
    Layer* layer;
    int64_t token;
    uint64_t releasedAhb;
    int fenceFd;
    bool destroy;
};

JavaVM* g_vm = nullptr;
jmethodID g_onCompleted = nullptr;
jmethodID g_onDestroyed = nullptr;
pthread_mutex_t g_queue_mutex = PTHREAD_MUTEX_INITIALIZER;
pthread_cond_t g_queue_cond = PTHREAD_COND_INITIALIZER;
std::deque<Event> g_queue;
bool g_worker_started = false;

void waitFence(int fd) {
    if (fd < 0) return;
    struct pollfd pfd = {fd, POLLIN, 0};
    int rc;
    do {
        rc = poll(&pfd, 1, kReleaseFenceTimeoutMs);
    } while (rc < 0 && errno == EINTR);
    if (rc == 0) LOGW("release fence wait timed out after %d ms", kReleaseFenceTimeoutMs);
    close(fd);
}

void* workerMain(void*) {
    JNIEnv* env = nullptr;
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK || !env) {
        LOGE("worker failed to attach to the JVM");
        return nullptr;
    }
    for (;;) {
        pthread_mutex_lock(&g_queue_mutex);
        while (g_queue.empty()) pthread_cond_wait(&g_queue_cond, &g_queue_mutex);
        Event ev = g_queue.front();
        g_queue.pop_front();
        pthread_mutex_unlock(&g_queue_mutex);

        waitFence(ev.fenceFd);
        Layer* layer = ev.layer;
        if (ev.destroy) {
            env->CallVoidMethod(layer->javaRef, g_onDestroyed);
        } else {
            env->CallVoidMethod(layer->javaRef, g_onCompleted, (jlong)ev.token, (jlong)ev.releasedAhb);
        }
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        if (layer->pendingCallbacks.fetch_sub(1) == 1 && layer->destroyed.load()) {
            env->DeleteGlobalRef(layer->javaRef);
            delete layer;
        }
    }
    return nullptr;
}

void enqueue(const Event& ev) {
    pthread_mutex_lock(&g_queue_mutex);
    if (!g_worker_started) {
        pthread_t tid;
        pthread_attr_t attr;
        pthread_attr_init(&attr);
        pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
        if (pthread_create(&tid, &attr, workerMain, nullptr) == 0) {
            g_worker_started = true;
        } else {
            LOGE("failed to start the release worker");
        }
        pthread_attr_destroy(&attr);
    }
    g_queue.push_back(ev);
    pthread_cond_signal(&g_queue_cond);
    pthread_mutex_unlock(&g_queue_mutex);
}

int takePreviousReleaseFence(ASurfaceTransactionStats* stats, ASurfaceControl* sc) {
    const Api& a = api();
    ASurfaceControl** controls = nullptr;
    size_t count = 0;
    a.statsGetControls(stats, &controls, &count);
    bool present = false;
    for (size_t i = 0; i < count; i++) {
        if (controls[i] == sc) {
            present = true;
            break;
        }
    }
    if (controls) a.statsReleaseControls(controls);
    return present ? a.statsGetPreviousReleaseFence(stats, sc) : -1;
}

void onTransactionComplete(void* context, ASurfaceTransactionStats* stats) {
    TxContext* ctx = static_cast<TxContext*>(context);
    Event ev;
    ev.layer = ctx->layer;
    ev.token = ctx->token;
    ev.releasedAhb = ctx->previousAhb;
    ev.destroy = ctx->destroy;
    ev.fenceFd = (ctx->previousAhb != 0 || ctx->destroy) ? takePreviousReleaseFence(stats, ctx->layer->sc) : -1;
    delete ctx;
    enqueue(ev);
}

void applyWithCallback(ASurfaceTransaction* tx, Layer* layer, int64_t token, uint64_t previousAhb, bool destroy) {
    const Api& a = api();
    TxContext* ctx = new TxContext{layer, token, previousAhb, destroy};
    layer->pendingCallbacks.fetch_add(1);
    a.txSetOnComplete(tx, ctx, onTransactionComplete);
    a.txApply(tx);
    a.txDelete(tx);
}

bool cacheMethods(JNIEnv* env, jobject thiz) {
    if (g_onCompleted && g_onDestroyed) return true;
    jclass cls = env->GetObjectClass(thiz);
    if (!cls) return false;
    g_onCompleted = env->GetMethodID(cls, "onTransactionCompleted", "(JJ)V");
    g_onDestroyed = env->GetMethodID(cls, "onDestroyed", "()V");
    env->DeleteLocalRef(cls);
    if (!g_onCompleted || !g_onDestroyed) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGE("callback methods not found");
        return false;
    }
    return true;
}

}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_winlator_cmod_runtime_display_composition_DirectCompositionLayer_nativeIsSupported(JNIEnv*, jclass) {
    return api().available ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_runtime_display_composition_DirectCompositionLayer_nativeCreate(
        JNIEnv* env, jobject thiz, jobject surface) {
    const Api& a = api();
    if (!a.available || !surface) return 0;
    if (!g_vm && env->GetJavaVM(&g_vm) != JNI_OK) {
        LOGE("GetJavaVM failed");
        return 0;
    }
    if (!cacheMethods(env, thiz)) return 0;

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        LOGW("ANativeWindow_fromSurface returned null");
        return 0;
    }
    ASurfaceControl* sc = a.createFromWindow(window, "winnative-direct-composition");
    ANativeWindow_release(window);
    if (!sc) {
        LOGW("ASurfaceControl_createFromWindow failed");
        return 0;
    }

    ASurfaceTransaction* tx = a.txCreate();
    if (!tx) {
        a.scRelease(sc);
        return 0;
    }
    a.txSetVisibility(tx, sc, kVisibilityHide);
    a.txSetZOrder(tx, sc, kLayerZOrder);
    if (a.txSetBufferDataSpace) a.txSetBufferDataSpace(tx, sc, ADATASPACE_SRGB);
    if (a.txSetBufferTransparency) a.txSetBufferTransparency(tx, sc, kTransparencyOpaque);
    if (a.txSetBufferTransform) a.txSetBufferTransform(tx, sc, 0);
    a.txApply(tx);
    a.txDelete(tx);

    Layer* layer = new Layer();
    layer->sc = sc;
    layer->javaRef = env->NewGlobalRef(thiz);
    if (!layer->javaRef) {
        a.scRelease(sc);
        delete layer;
        return 0;
    }
    LOGI("layer created sc=%p", (void*)sc);
    return (jlong)(uintptr_t)layer;
}

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_runtime_display_composition_DirectCompositionLayer_nativePresent(
        JNIEnv*, jclass, jlong handle, jlong token, jlong ahbPtr,
        jint cropX, jint cropY, jint cropW, jint cropH,
        jint dstX, jint dstY, jint dstW, jint dstH) {
    Layer* layer = reinterpret_cast<Layer*>((uintptr_t)handle);
    AHardwareBuffer* ahb = reinterpret_cast<AHardwareBuffer*>((uintptr_t)ahbPtr);
    if (!layer || !ahb || dstW <= 0 || dstH <= 0 || dstX < 0 || dstY < 0) return 0;
    const Api& a = api();

    AHardwareBuffer_Desc desc;
    memset(&desc, 0, sizeof(desc));
    AHardwareBuffer_describe(ahb, &desc);
    if (desc.width == 0 || desc.height == 0) return 0;
    if (!(desc.usage & AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE)) return 0;

    int32_t bufW = (int32_t)desc.width;
    int32_t bufH = (int32_t)desc.height;
    int32_t left = cropX < 0 ? 0 : (cropX > bufW ? bufW : cropX);
    int32_t top = cropY < 0 ? 0 : (cropY > bufH ? bufH : cropY);
    int32_t right = cropX + cropW > bufW ? bufW : cropX + cropW;
    int32_t bottom = cropY + cropH > bufH ? bufH : cropY + cropH;
    if (right - left <= 0 || bottom - top <= 0) return 0;
    ARect crop = {left, top, right, bottom};

    ASurfaceTransaction* tx = a.txCreate();
    if (!tx) return 0;
    a.txSetBuffer(tx, layer->sc, ahb, -1);
    if (a.txSetPosition && a.txSetScale && a.txSetCrop) {
        a.txSetCrop(tx, layer->sc, &crop);
        a.txSetPosition(tx, layer->sc, dstX, dstY);
        a.txSetScale(tx, layer->sc,
                     (float)dstW / (float)(right - left),
                     (float)dstH / (float)(bottom - top));
    } else {
        ARect dst = {dstX, dstY, dstX + dstW, dstY + dstH};
        a.txSetGeometry(tx, layer->sc, &crop, &dst, 0);
    }
    a.txSetVisibility(tx, layer->sc, kVisibilityShow);

    uint64_t previous = layer->currentAhb;
    layer->currentAhb = (uint64_t)ahbPtr;
    applyWithCallback(tx, layer, token, previous, false);
    return (desc.usage & AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY) ? 2 : 1;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_runtime_display_composition_DirectCompositionLayer_nativeDestroy(
        JNIEnv* env, jclass, jlong handle) {
    (void)env;
    Layer* layer = reinterpret_cast<Layer*>((uintptr_t)handle);
    if (!layer || layer->destroyed.exchange(true)) return;
    const Api& a = api();
    uint64_t previous = layer->currentAhb;
    layer->currentAhb = 0;
    ASurfaceTransaction* tx = a.txCreate();
    if (tx) {
        a.txSetVisibility(tx, layer->sc, kVisibilityHide);
        a.txReparent(tx, layer->sc, nullptr);
        applyWithCallback(tx, layer, -1, previous, true);
    } else {
        layer->pendingCallbacks.fetch_add(1);
        enqueue(Event{layer, -1, previous, -1, true});
    }
    LOGI("layer destroyed sc=%p", (void*)layer->sc);
    a.scRelease(layer->sc);
}

}
