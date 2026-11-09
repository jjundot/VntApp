package com.rustvnt.vntapp;

import java.io.File;
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
            boolean finished = process.waitFor(8, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.exitValue() == 0 && out.contains("uid=0");
        } catch (Exception ignored) {
            return false;
        }
    }

    static String runSu(String command) throws Exception {
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        boolean finished = process.waitFor(15, TimeUnit.SECONDS);
        String out;
        try (InputStream in = process.getInputStream()) {
            out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("su 命令超时");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(out.isBlank() ? ("su 失败，退出码 " + process.exitValue()) : out.trim());
        }
        return out;
    }

    private static String whichSu() {
        try {
            Process process = new ProcessBuilder("sh", "-c", "command -v su").redirectErrorStream(true).start();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.exitValue() == 0 && !out.isEmpty() ? out : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
