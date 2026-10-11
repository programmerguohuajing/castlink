// Host roundtrip test for the exact ALAC decoder sources compiled into libalac.so.
//
// Validates two things the sandbox cannot test on-device:
//  1. The 24-byte big-endian magic cookie built by AlacDecoder.buildMagicCookie()
//     (replicated here byte-for-byte) is accepted by ALACDecoder::Init() and yields
//     frameLength=352, 16-bit, stereo, 44100 Hz.
//  2. A real ALAC frame produced by Apple's encoder decodes back to identical PCM
//     through the same Decode() path the JNI bridge (alac_jni.cpp) calls.
//
// Build: g++ alac_host_test.cpp <decoder+encoder sources> -o alac_host_test
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "ALACAudioTypes.h"
#include "ALACBitUtilities.h"
#include "ALACDecoder.h"
#include "ALACEncoder.h"

static int failures = 0;
#define CHECK(cond, msg) do { \
    if (!(cond)) { printf("FAIL: %s\n", msg); failures++; } \
    else { printf("ok: %s\n", msg); } } while (0)

// Byte-for-byte replica of AlacDecoder.buildMagicCookie(sampleRate, channels, frameLength)
// with the AirPlay tuning constants (pb=40, mb=10, kb=14, maxRun=255, bitDepth=16).
static void buildMagicCookie(uint8_t c[24], uint32_t sampleRate, uint8_t channels, uint32_t frameLength) {
    auto put32 = [&](int off, uint32_t v) {
        c[off] = (v >> 24) & 0xFF; c[off+1] = (v >> 16) & 0xFF;
        c[off+2] = (v >> 8) & 0xFF; c[off+3] = v & 0xFF;
    };
    put32(0, frameLength);
    c[4] = 0; c[5] = 16; c[6] = 40; c[7] = 10; c[8] = 14; c[9] = channels;
    c[10] = 0; c[11] = 255;
    put32(12, 0); put32(16, 0); put32(20, sampleRate);
}

int main() {
    const uint32_t kSampleRate = 44100, kChannels = 2, kFrames = 352;

    // 1. Encode 352 stereo 16-bit samples with Apple's encoder.
    int16_t pcm[kFrames * kChannels];
    for (uint32_t i = 0; i < kFrames; i++) {
        int16_t s = (int16_t)(20000.0 * sin(2.0 * M_PI * 440.0 * i / kSampleRate));
        pcm[i * 2] = s; pcm[i * 2 + 1] = (int16_t)(s / 2);
    }
    AudioFormatDescription inFmt = {};
    inFmt.mSampleRate = kSampleRate;
    inFmt.mFormatID = kALACFormatLinearPCM;
    inFmt.mFormatFlags = kALACFormatFlagIsSignedInteger | kALACFormatFlagIsPacked;  // LE 16-bit
    inFmt.mBytesPerPacket = kChannels * 2;
    inFmt.mFramesPerPacket = 1;
    inFmt.mBytesPerFrame = kChannels * 2;
    inFmt.mChannelsPerFrame = kChannels;
    inFmt.mBitsPerChannel = 16;

    AudioFormatDescription outFmt = inFmt;
    outFmt.mFormatID = kALACFormatAppleLossless;
    outFmt.mFormatFlags = 1;  // kTestFormatFlag_16BitSourceData → InitializeEncoder 得 mBitDepth=16
    outFmt.mFramesPerPacket = kFrames;
    outFmt.mBytesPerPacket = 0;

    ALACEncoder encoder;
    encoder.SetFrameSize(kFrames);
    CHECK(encoder.InitializeEncoder(outFmt) == ALAC_noErr, "encoder InitializeEncoder");

    uint8_t alacFrame[8192];
    int32_t ioBytes = sizeof(pcm);  // 入参 = 输入 PCM 字节数；出参 = 编码后字节数
    int32_t encStatus = encoder.Encode(inFmt, inFmt, (uint8_t*)pcm, alacFrame, &ioBytes);
    if (encStatus != ALAC_noErr) printf("  (encode status=%d)\n", encStatus);
    int32_t alacBytes = (encStatus == ALAC_noErr) ? ioBytes : 0;
    CHECK(encStatus == ALAC_noErr && alacBytes > 0, "encoder Encode produced a frame");

    // 2. Init the decoder with the Kotlin-replica magic cookie (what JNI nativeInit does).
    uint8_t cookie[24];
    buildMagicCookie(cookie, kSampleRate, kChannels, kFrames);
    ALACDecoder decoder;
    CHECK(decoder.Init(cookie, sizeof(cookie)) == ALAC_noErr, "decoder Init with Kotlin-layout cookie");
    CHECK(decoder.mConfig.frameLength == kFrames, "config frameLength == 352");
    CHECK(decoder.mConfig.numChannels == kChannels, "config numChannels == 2");
    CHECK(decoder.mConfig.bitDepth == 16, "config bitDepth == 16");
    CHECK(decoder.mConfig.sampleRate == kSampleRate, "config sampleRate == 44100");

    // 3. Decode via the same calls alac_jni.cpp makes; compare PCM sample-for-sample.
    BitBuffer bits;
    BitBufferInit(&bits, alacFrame, (uint32_t)alacBytes);
    int16_t out[kFrames * kChannels];
    memset(out, 0, sizeof(out));
    uint32_t outNumSamples = 0;
    int32_t decStatus = decoder.Decode(&bits, (uint8_t*)out, kFrames, kChannels, &outNumSamples);
    CHECK(decStatus == ALAC_noErr, "decoder Decode ok");
    CHECK(outNumSamples == kFrames, "decoded 352 samples");
    CHECK(memcmp(pcm, out, sizeof(pcm)) == 0, "PCM roundtrip identical");

    if (failures == 0) printf("\nALL HOST ALAC TESTS PASSED\n");
    else printf("\n%d FAILURES\n", failures);
    return failures == 0 ? 0 : 1;
}
