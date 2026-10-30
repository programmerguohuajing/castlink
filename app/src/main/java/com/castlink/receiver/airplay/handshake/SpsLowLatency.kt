package com.castlink.receiver.airplay.handshake

import java.io.ByteArrayOutputStream

/**
 * SpsLowLatency — 给 H.264 SPS 补上 VUI `bitstream_restriction`（max_num_reorder_frames=0、
 * max_dec_frame_buffering=max(num_ref_frames,1)），让解码器“解一帧出一帧”。
 *
 * 为什么：iPhone 镜像的 SPS 没有 VUI（18 字节），解码器因此按 level 推算 DPB 大小（5.0 级
 * 1080p = 13 帧），在输入帧数 − 输出帧数 = 13 时才开始吐帧——实测软解 `fed - out` 恒为 13，
 * 约 0.4~0.5 秒的纯解码器内部延迟，是声画不同步的主要来源之一；静止画面时最后十几帧还会
 * 压在解码器里出不来。AirPlay 镜像是实时编码、无 B 帧/无重排序，把重排序数声明为 0 是安全的
 * （Moonlight 等低延迟串流客户端对安卓解码器做的是同一件事）。
 *
 * 只改 SPS 尾部（VUI 之前的比特原样拷贝），所以不会改变分辨率/profile/level/参考帧数；
 * 解析失败或本来就声明了 0 重排序时返回 null，调用方继续用原 SPS。
 */
internal object SpsLowLatency {

    /** 输入/输出均为“裸 NAL”：首字节是 NAL 头（0x67），无起始码。 */
    fun patch(sps: ByteArray): ByteArray? = try {
        patchInternal(sps)
    } catch (e: Exception) {
        null
    }

    private class Bits(val d: ByteArray) {
        var pos = 0
        val size get() = d.size * 8
        fun bit(): Int {
            if (pos >= size) throw IndexOutOfBoundsException("SPS truncated")
            val b = (d[pos ushr 3].toInt() shr (7 - (pos and 7))) and 1
            pos++
            return b
        }
        fun u(n: Int): Long { var v = 0L; repeat(n) { v = (v shl 1) or bit().toLong() }; return v }
        fun ue(): Long {
            var zeros = 0
            while (bit() == 0) { zeros++; if (zeros > 32) throw IllegalStateException("bad ue") }
            return if (zeros == 0) 0 else ((1L shl zeros) - 1) + u(zeros)
        }
        fun se(): Long { val k = ue(); return if (k and 1L == 1L) (k + 1) / 2 else -(k / 2) }
    }

    private class BitWriter {
        private val out = ByteArrayOutputStream()
        private var cur = 0
        private var n = 0
        fun bit(b: Int) {
            cur = (cur shl 1) or (b and 1); n++
            if (n == 8) { out.write(cur); cur = 0; n = 0 }
        }
        fun u(v: Long, bits: Int) { for (i in bits - 1 downTo 0) bit(((v shr i) and 1L).toInt()) }
        fun ue(v: Long) {
            val x = v + 1
            val len = 64 - java.lang.Long.numberOfLeadingZeros(x)
            repeat(len - 1) { bit(0) }
            u(x, len)
        }
        /** 拷贝 src 的 [from, to) 比特。 */
        fun copy(src: ByteArray, from: Int, to: Int) {
            for (p in from until to) bit((src[p ushr 3].toInt() shr (7 - (p and 7))) and 1)
        }
        fun finishWithTrailingBits(): ByteArray {
            bit(1)
            while (n != 0) bit(0)
            return out.toByteArray()
        }
    }

    private fun unescape(nal: ByteArray, from: Int): ByteArray {
        val o = ByteArrayOutputStream()
        var zeros = 0
        for (i in from until nal.size) {
            val b = nal[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) { zeros = 0; continue }      // emulation_prevention_three_byte
            o.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return o.toByteArray()
    }

    private fun escape(rbsp: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        var zeros = 0
        for (x in rbsp) {
            val b = x.toInt() and 0xFF
            if (zeros >= 2 && b <= 3) { o.write(3); zeros = 0 }
            o.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return o.toByteArray()
    }

    private fun skipScalingList(r: Bits, size: Int) {
        var last = 8
        var next = 8
        for (j in 0 until size) {
            if (next != 0) {
                val delta = r.se()
                next = ((last + delta + 256) % 256).toInt()
            }
            last = if (next == 0) last else next
        }
    }

    private fun skipHrd(r: Bits) {
        val cnt = r.ue().toInt() + 1
        r.u(4); r.u(4)
        repeat(cnt) { r.ue(); r.ue(); r.bit() }
        r.u(5); r.u(5); r.u(5); r.u(5)
    }

    private fun patchInternal(sps: ByteArray): ByteArray? {
        if (sps.size < 5 || (sps[0].toInt() and 0x1F) != 7) return null
        val rbsp = unescape(sps, 1)
        val r = Bits(rbsp)
        val profile = r.u(8).toInt()
        r.u(8); r.u(8)                                   // constraint flags + level_idc
        r.ue()                                           // seq_parameter_set_id
        if (profile in HIGH_PROFILES) {
            val chroma = r.ue().toInt()
            if (chroma == 3) r.bit()
            r.ue(); r.ue(); r.bit()                      // bit depths, qpprime_y_zero_transform_bypass
            if (r.bit() == 1) {                          // seq_scaling_matrix_present_flag
                for (i in 0 until (if (chroma != 3) 8 else 12)) {
                    if (r.bit() == 1) skipScalingList(r, if (i < 6) 16 else 64)
                }
            }
        }
        r.ue()                                           // log2_max_frame_num_minus4
        when (r.ue().toInt()) {                          // pic_order_cnt_type
            0 -> r.ue()
            1 -> {
                r.bit(); r.se(); r.se()
                val n = r.ue().toInt()
                repeat(n) { r.se() }
            }
        }
        val numRefFrames = r.ue().toInt()
        r.bit()                                          // gaps_in_frame_num_value_allowed_flag
        r.ue(); r.ue()                                   // width/height in MBs
        if (r.bit() == 0) r.bit()                        // frame_mbs_only_flag==0 → mb_adaptive_frame_field_flag
        r.bit()                                          // direct_8x8_inference_flag
        if (r.bit() == 1) { r.ue(); r.ue(); r.ue(); r.ue() }   // frame cropping
        val vuiFlagPos = r.pos
        val vuiPresent = r.bit() == 1
        var restrPos = r.pos                             // 无 VUI 时：写新 VUI 的起点（vui 标志之后）
        if (vuiPresent) {
            if (r.bit() == 1) { if (r.u(8) == 255L) r.u(32) }          // aspect_ratio
            if (r.bit() == 1) r.bit()                                   // overscan
            if (r.bit() == 1) { r.u(3); r.bit(); if (r.bit() == 1) r.u(24) }   // video_signal_type
            if (r.bit() == 1) { r.ue(); r.ue() }                        // chroma_loc
            if (r.bit() == 1) { r.u(32); r.u(32); r.bit() }             // timing_info
            val nal = r.bit() == 1
            if (nal) skipHrd(r)
            val vcl = r.bit() == 1
            if (vcl) skipHrd(r)
            if (nal || vcl) r.bit()                                     // low_delay_hrd_flag
            r.bit()                                                     // pic_struct_present_flag
            restrPos = r.pos
            if (r.bit() == 1) {                                         // 已有 bitstream_restriction
                r.bit(); r.ue(); r.ue(); r.ue(); r.ue()
                val reorder = r.ue()
                r.ue()
                if (reorder == 0L) return null                          // 本来就是低延迟声明，无需改
            }
        }

        val w = BitWriter()
        if (vuiPresent) {
            w.copy(rbsp, 0, restrPos)                    // 含 vui 标志与全部前置 VUI 字段
        } else {
            w.copy(rbsp, 0, vuiFlagPos)
            w.bit(1)                                     // vui_parameters_present_flag = 1
            repeat(8) { w.bit(0) }  // aspect/overscan/video_signal/chroma_loc/timing/nal_hrd/vcl_hrd/pic_struct = 0
        }
        w.bit(1)                                         // bitstream_restriction_flag
        w.bit(1)                                         // motion_vectors_over_pic_boundaries_flag
        w.ue(2); w.ue(1)                                 // max_bytes_per_pic_denom, max_bits_per_mb_denom
        w.ue(15); w.ue(15)                               // log2_max_mv_length_horizontal / vertical
        w.ue(0)                                          // max_num_reorder_frames = 0
        w.ue(maxOf(numRefFrames, 1).toLong())            // max_dec_frame_buffering
        val outRbsp = w.finishWithTrailingBits()
        return byteArrayOf(sps[0]) + escape(outRbsp)
    }

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
}
