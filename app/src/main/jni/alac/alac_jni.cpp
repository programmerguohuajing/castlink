/*
 * alac_jni.cpp — JNI bridge between CastLink's AlacDecoder.kt and Apple's open-source
 * ALAC decoder (see codec/ sources in this directory; Apache License 2.0, macosforge/alac).
 *
 * The Kotlin side owns one native ALACDecoder instance per audio stream (an opaque jlong
 * handle); decode() is single-threaded (the audio playback thread). Memory is plain
 * calloc/free + placement new so the .so has no libc++_shared dependency.
 *
 * Kotlin signatures (com.castlink.receiver.airplay.handshake.AlacDecoder):
 *   nativeInit(ByteArray magicCookie): Long            -> JNI name below
 *   nativeDecode(Long handle, ByteArray in, Int len, ByteArray out): Int  (bytes written, <0 on error)
 *   nativeRelease(Long handle)
 */

#include <jni.h>

#include <new>        // placement new only — no heap operator new, no libc++ dependency
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "ALACDecoder.h"
#include "ALACBitUtilities.h"
#include "ALACAudioTypes.h"

namespace {

constexpr int32_t kDecodeErrParam = -1;   // bad handle / bad args
constexpr int32_t kDecodeErrNative = -2;  // native Decode() returned an error status

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_castlink_receiver_airplay_handshake_AlacDecoder_nativeInit(
        JNIEnv* env, jobject /*thiz*/, jbyteArray magicCookie) {
    if (magicCookie == nullptr) return 0;
    const jsize cookieLen = env->GetArrayLength(magicCookie);
    if (cookieLen <= 0) return 0;

    uint8_t* cookie = static_cast<uint8_t*>(malloc(static_cast<size_t>(cookieLen)));
    if (cookie == nullptr) return 0;
    env->GetByteArrayRegion(magicCookie, 0, cookieLen, reinterpret_cast<jbyte*>(cookie));

    // calloc keeps the ctor invariant (it only zeroes members) without operator new.
    void* mem = calloc(1, sizeof(ALACDecoder));
    ALACDecoder* decoder = nullptr;
    if (mem != nullptr) {
        decoder = new (mem) ALACDecoder();  // placement new — no library support needed
        if (decoder->Init(cookie, static_cast<uint32_t>(cookieLen)) != ALAC_noErr) {
            decoder->~ALACDecoder();
            free(mem);
            decoder = nullptr;
        }
    }
    free(cookie);
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(decoder));
}

JNIEXPORT jint JNICALL
Java_com_castlink_receiver_airplay_handshake_AlacDecoder_nativeDecode(
        JNIEnv* env, jobject /*thiz*/, jlong handle,
        jbyteArray input, jint inputLen, jbyteArray output) {
    ALACDecoder* decoder = reinterpret_cast<ALACDecoder*>(static_cast<uintptr_t>(handle));
    if (decoder == nullptr || input == nullptr || output == nullptr || inputLen <= 0) {
        return kDecodeErrParam;
    }
    const jsize inArrayLen = env->GetArrayLength(input);
    if (inputLen > inArrayLen) inputLen = inArrayLen;

    uint8_t* inBuf = static_cast<uint8_t*>(malloc(static_cast<size_t>(inputLen)));
    if (inBuf == nullptr) return kDecodeErrParam;
    env->GetByteArrayRegion(input, 0, inputLen, reinterpret_cast<jbyte*>(inBuf));

    const uint32_t channels = decoder->mConfig.numChannels;
    const uint32_t maxSamples = decoder->mConfig.frameLength;
    const jsize outCap = env->GetArrayLength(output);

    int32_t bytesOut = kDecodeErrNative;
    if (channels > 0 && maxSamples > 0) {
        jbyte* outBuf = env->GetByteArrayElements(output, nullptr);
        if (outBuf != nullptr) {
            BitBuffer bits;
            BitBufferInit(&bits, inBuf, static_cast<uint32_t>(inputLen));
            uint32_t outNumSamples = 0;
            const int32_t status = decoder->Decode(
                    &bits, reinterpret_cast<uint8_t*>(outBuf),
                    maxSamples, channels, &outNumSamples);
            if (status == ALAC_noErr) {
                const uint32_t bytesPerSample = decoder->mConfig.bitDepth / 8;
                const int64_t total = static_cast<int64_t>(outNumSamples) * channels * bytesPerSample;
                // Clamp to the Java output buffer capacity — never write past it.
                bytesOut = static_cast<int32_t>(total > outCap ? outCap : total);
            }
            env->ReleaseByteArrayElements(output, outBuf, 0);  // commit back to Java
        }
    }
    free(inBuf);
    return bytesOut;
}

JNIEXPORT void JNICALL
Java_com_castlink_receiver_airplay_handshake_AlacDecoder_nativeRelease(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    ALACDecoder* decoder = reinterpret_cast<ALACDecoder*>(static_cast<uintptr_t>(handle));
    if (decoder != nullptr) {
        decoder->~ALACDecoder();  // frees the internal mix/predictor buffers
        free(decoder);
    }
}

}  // extern "C"
