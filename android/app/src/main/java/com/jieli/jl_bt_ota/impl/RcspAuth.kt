package com.jieli.jl_bt_ota.impl

/**
 * JNI binding for the vendor's `libjl_ota_auth.so`. The library is vendor property and is NOT part of this
 * repository. Without it every call throws UnsatisfiedLinkError (caught by callers) and the auth probe is simply
 * unavailable. To experiment, place your own copy under android/app/src/main/jniLibs/<abi>/ (git-ignored).
 * Not needed for the SY01, which has no JieLi ae00 service.
 */
class RcspAuth {
    companion object {
        init {
            try {
                System.loadLibrary("jl_ota_auth")
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    external fun nativeInit(): Boolean
    external fun getRandomAuthData(): ByteArray
    external fun getEncryptedAuthData(data: ByteArray): ByteArray
    external fun setLinkKey(data: ByteArray): Int

    init {
        try {
            nativeInit()
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    val resetAuthCmd: ByteArray = byteArrayOf(
        0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), 0xC0.toByte(),
        0x06, 0x00, 0x02, 0x00, 0x01, 0xEF.toByte()
    )

    // PASS lives in JlAuthSession.PASS (5 bytes)
}
