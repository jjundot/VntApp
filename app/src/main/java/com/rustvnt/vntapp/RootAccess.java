package com.rustvnt.vntapp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** 检测设备是否具备可用的 su（用于系统级 Wi‑Fi 子网接管选项）。 */
final class RootAccess {
    private static final String[] SU_PATHS = {
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/data/local/tmp/su",
            "/data/adb/magisk/busybox",
    };

    private static Boolean presentCache;

    private RootAccess() {}

    /** 仅检查常见 su/Magisk 路径，不弹授权框；用于 UI 置灰。 */
    static synchronized boolean isPresent() {
        if (presentCache != null) return presentCache;
        for (String path : SU_PATHS) {
            if (new File(path).exists()) {
                presentCache = true;
                return true;
            }
        }
        presentCache = whichSu() != null;
        return presentCache;
    }

    /** 真正执行一次 `su -c id`，可能弹出 Magisk/SuperSU 授权。 */
    static boolean canExecute() {
        if (!isPresent()) return false;
        try {
            Process process = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
            if (!waitForExit(process, 8_000)) {
                process.destroy();
                return false;
            }
            String out = readUtf8(process.getInputStream());
            return process.exitValue() == 0 && out.contains("uid=0");
        } catch (Exception ignored) {
            return false;
        }
    }

    static String runSu(String command) throws Exception {
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        if (!waitForExit(process, 15_000)) {
            process.destroy();
            throw new IllegalStateException("su 命令超时");
        }
        String out = readUtf8(process.getInputStream());
        if (process.exitValue() != 0) {
            throw new IllegalStateException(out.isBlank() ? ("su 失败，退出码 " + process.exitValue()) : out.trim());
        }
        return out;
    }

    private static String whichSu() {
        try {
            Process process = new ProcessBuilder("sh", "-c", "command -v su").redirectErrorStream(true).start();
            if (!waitForExit(process, 3_000)) {
                process.destroy();
                return null;
            }
            String out = readUtf8(process.getInputStream()).trim();
            return process.exitValue() == 0 && !out.isEmpty() ? out : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** {@link Process#waitFor(long, TimeUnit)} is API 26; minSdk is 24. */
    private static boolean waitForExit(Process process, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            try {
                process.exitValue();
                return true;
            } catch (IllegalThreadStateException stillRunning) {
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMs <= 0) return false;
                Thread.sleep(Math.min(50L, remainingMs));
            }
        }
    }

    /** {@link InputStream#readAllBytes()} is API 33. */
    private static String readUtf8(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        try (InputStream source = in) {
            int count;
            while ((count = source.read(chunk)) != -1) buffer.write(chunk, 0, count);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
