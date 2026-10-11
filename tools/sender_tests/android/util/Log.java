package android.util;

/**
 * JVM 单测桩：让 protocol/SignalingServer.kt 与 protocol/RtpReceiver.kt
 * 在不引入 android.jar 的情况下参与编译与运行。
 */
public class Log {
    public static int d(String tag, String msg) { return 0; }
    public static int i(String tag, String msg) { return 0; }
    public static int w(String tag, String msg) { return 0; }
    public static int w(String tag, String msg, Throwable tr) { return 0; }
    public static int e(String tag, String msg) { return 0; }
    public static int e(String tag, String msg, Throwable tr) { return 0; }
}
