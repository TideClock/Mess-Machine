/*
 * Mess Machine's AAudio output (Nov 21, L4). The Java engine still makes the sound on its own thread; it writes
 * each block into a small ring here, and AAudio's callback (Android's fast, low-latency path, MMAP when the phone
 * has it) reads the ring. Nothing in the callback allocates, locks or calls into Java.
 *
 * One stream at a time. All counts are frames (one frame = two floats, left and right).
 */
#include <aaudio/AAudio.h>
#include <jni.h>
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>
#include <time.h>

#define RING (1 << 14) /* 16384 frames, about 340 ms at 48 kHz: far more than ever used, so it never wraps over itself */

static AAudioStream *stream = NULL;
static float ring[RING * 2];
static _Atomic int64_t wpos = 0, rpos = 0;
static _Atomic int32_t target = 512;   /* how full the writer keeps the ring */
static _Atomic int32_t dry = 0;        /* callbacks that found the ring short: a click */
static _Atomic int32_t dead = 0;       /* the stream died (headphones out, device change): Java falls back and restarts */
static _Atomic int32_t primed = 0;     /* no clicks counted before the first full ring */

static aaudio_data_callback_result_t onData(AAudioStream *s, void *user, void *data, int32_t n) {
    (void) s; (void) user;
    float *out = (float *) data;
    int64_t r = atomic_load_explicit(&rpos, memory_order_relaxed);
    int64_t w = atomic_load_explicit(&wpos, memory_order_acquire);
    int64_t have = w - r;
    int32_t take = have < n ? (int32_t) have : n;
    int32_t at = (int32_t) (r & (RING - 1));
    int32_t first = take < RING - at ? take : RING - at;
    memcpy(out, ring + at * 2, (size_t) first * 2 * sizeof(float));
    if (take > first) memcpy(out + first * 2, ring, (size_t) (take - first) * 2 * sizeof(float));
    if (take < n) {
        memset(out + take * 2, 0, (size_t) (n - take) * 2 * sizeof(float));
        if (atomic_load_explicit(&primed, memory_order_relaxed)) atomic_fetch_add(&dry, 1);
    }
    atomic_store_explicit(&rpos, r + take, memory_order_release);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void onError(AAudioStream *s, void *user, aaudio_result_t err) {
    (void) s; (void) user; (void) err;
    atomic_store(&dead, 1);
}

static AAudioStream *build(int32_t rate, aaudio_sharing_mode_t share) {
    AAudioStreamBuilder *b = NULL;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return NULL;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, share);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setSampleRate(b, rate);
    AAudioStreamBuilder_setDataCallback(b, onData, NULL);
    AAudioStreamBuilder_setErrorCallback(b, onError, NULL);
    AAudioStream *st = NULL;
    aaudio_result_t res = AAudioStreamBuilder_openStream(b, &st);
    AAudioStreamBuilder_delete(b);
    if (res != AAUDIO_OK) return NULL;
    /* the engine renders at its own rate: a stream at any other rate would play everything at the wrong pitch */
    if (AAudioStream_getSampleRate(st) != rate || AAudioStream_getChannelCount(st) != 2 || AAudioStream_getFormat(st) != AAUDIO_FORMAT_PCM_FLOAT) {
        AAudioStream_close(st);
        return NULL;
    }
    return st;
}

/* Open and start. Returns the stream's burst in frames, or a negative number when AAudio is not usable here. */
JNIEXPORT jint JNICALL Java_com_tideclock_messmachine_NativeOut_open(JNIEnv *env, jobject thiz, jint rate, jint fill) {
    (void) env; (void) thiz;
    if (stream) return -2;
    atomic_store(&wpos, 0); atomic_store(&rpos, 0); atomic_store(&dry, 0); atomic_store(&dead, 0); atomic_store(&primed, 0);
    atomic_store(&target, fill > 0 && fill < RING / 2 ? fill : 512);
    AAudioStream *st = build(rate, AAUDIO_SHARING_MODE_EXCLUSIVE);
    if (!st) st = build(rate, AAUDIO_SHARING_MODE_SHARED);
    if (!st) return -1;
    int32_t burst = AAudioStream_getFramesPerBurst(st);
    if (burst <= 0) burst = 192;
    /* two bursts in AAudio's own buffer: the usual low-latency setting */
    AAudioStream_setBufferSizeInFrames(st, burst * 2);
    if (AAudioStream_requestStart(st) != AAUDIO_OK) { AAudioStream_close(st); return -1; }
    stream = st;
    return burst;
}

/*
 * Write one block, waiting while the ring holds its target. Returns frames written, or -1 when the stream died or
 * was closed (the Java side then goes back to the old path).
 */
JNIEXPORT jint JNICALL Java_com_tideclock_messmachine_NativeOut_write(JNIEnv *env, jobject thiz, jfloatArray buf, jint frames, jint rate) {
    (void) thiz;
    if (!stream) return -1;
    for (;;) {
        if (atomic_load(&dead)) return -1;
        int64_t have = atomic_load_explicit(&wpos, memory_order_relaxed) - atomic_load_explicit(&rpos, memory_order_acquire);
        int32_t t = atomic_load_explicit(&target, memory_order_relaxed);
        if (have + frames <= t) break;
        if (!atomic_load_explicit(&primed, memory_order_relaxed)) atomic_store(&primed, 1);
        /* sleep about the time the extra takes to play, at least half a millisecond */
        int64_t extra = have + frames - t;
        int64_t ns = extra * 1000000000LL / (rate > 0 ? rate : 48000);
        if (ns < 500000) ns = 500000;
        if (ns > 20000000) ns = 20000000;
        struct timespec ts = { 0, (long) ns };
        nanosleep(&ts, NULL);
    }
    int64_t w = atomic_load_explicit(&wpos, memory_order_relaxed);
    int32_t at = (int32_t) (w & (RING - 1));
    int32_t first = frames < RING - at ? frames : RING - at;
    (*env)->GetFloatArrayRegion(env, buf, 0, first * 2, ring + at * 2);
    if (frames > first) (*env)->GetFloatArrayRegion(env, buf, first * 2, (frames - first) * 2, ring);
    atomic_store_explicit(&wpos, w + frames, memory_order_release);
    return frames;
}

JNIEXPORT void JNICALL Java_com_tideclock_messmachine_NativeOut_setFill(JNIEnv *env, jobject thiz, jint fill) {
    (void) env; (void) thiz;
    if (fill > 0 && fill < RING / 2) atomic_store(&target, fill);
}

/* Clicks so far: the ring running short plus the device's own underruns. */
JNIEXPORT jint JNICALL Java_com_tideclock_messmachine_NativeOut_clicks(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    int32_t x = stream ? AAudioStream_getXRunCount(stream) : 0;
    return atomic_load(&dry) + (x > 0 ? x : 0);
}

/*
 * Frames between the newest one written and the one leaving the speaker right now, from AAudio's timestamp;
 * -1 when it has none yet. now is System.nanoTime() (the same clock).
 */
JNIEXPORT jlong JNICALL Java_com_tideclock_messmachine_NativeOut_behind(JNIEnv *env, jobject thiz, jlong now, jint rate) {
    (void) env; (void) thiz;
    if (!stream) return -1;
    int64_t pos = 0, t = 0;
    if (AAudioStream_getTimestamp(stream, CLOCK_MONOTONIC, &pos, &t) != AAUDIO_OK || pos <= 0) return -1;
    int64_t played = pos + (now - t) * (int64_t) rate / 1000000000LL;
    return atomic_load(&wpos) - played;
}

/* "exclusive" (MMAP, the fastest), "shared", or "" when closed. */
JNIEXPORT jstring JNICALL Java_com_tideclock_messmachine_NativeOut_mode(JNIEnv *env, jobject thiz) {
    (void) thiz;
    const char *m = "";
    if (stream) {
        int fast = AAudioStream_getPerformanceMode(stream) == AAUDIO_PERFORMANCE_MODE_LOW_LATENCY;
        int ex = AAudioStream_getSharingMode(stream) == AAUDIO_SHARING_MODE_EXCLUSIVE;
        m = ex ? (fast ? "exclusive, low latency" : "exclusive") : (fast ? "shared, low latency" : "shared, slower path");
    }
    return (*env)->NewStringUTF(env, m);
}

JNIEXPORT jint JNICALL Java_com_tideclock_messmachine_NativeOut_deviceBuffer(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    return stream ? AAudioStream_getBufferSizeInFrames(stream) : 0;
}

JNIEXPORT void JNICALL Java_com_tideclock_messmachine_NativeOut_close(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    if (!stream) return;
    AAudioStream_requestStop(stream);
    AAudioStream_close(stream);
    stream = NULL;
}
