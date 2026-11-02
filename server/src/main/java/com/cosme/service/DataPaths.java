package com.cosme.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 数据目录的统一解析入口。
 *
 * <p>以前每个 Store 各自算 {@code System.getProperty("user.dir") + "data"}：
 * 在仓库根目录跑 jar 和在 {@code server/} 目录跑，会落到两个不同的目录，
 * 管理台里配好的 Key、审核过的成分换一种启动方式就"消失"。
 * 现在只在这里解析一次，所有落盘模块共用同一个绝对路径。</p>
 *
 * <p>优先级：{@code -Dcosme.data.dir} &gt; 环境变量 {@code COSME_DATA_DIR} &gt; 默认 {@code ~/.cosme/data}。
 * 默认值与启动目录无关，因此从任何位置启动都指向同一份数据。
 * 解析结果会打印在启动日志第一屏，方便排查"数据到底写哪了"。</p>
 */
public final class DataPaths {

    private static final String PROPERTY = "cosme.data.dir";
    private static final String ENV = "COSME_DATA_DIR";

    private static final Path DIR = resolveDir();

    private DataPaths() {
    }

    /** 数据根目录（绝对路径，已规范化）。 */
    public static Path dir() {
        return DIR;
    }

    /** 数据根目录下的子路径，例如 {@code resolve("reports")}。 */
    public static Path resolve(String... more) {
        Path path = DIR;
        for (String part : more) {
            if (part != null && !part.trim().isEmpty()) {
                path = path.resolve(part.trim());
            }
        }
        return path;
    }

    private static Path resolveDir() {
        String source;
        String custom = System.getProperty(PROPERTY);
        source = "-D" + PROPERTY;

        if (custom == null || custom.trim().isEmpty()) {
            custom = System.getenv(ENV);
            source = "环境变量 " + ENV;
        }

        Path dir;
        if (custom != null && !custom.trim().isEmpty()) {
            dir = Paths.get(custom.trim()).toAbsolutePath().normalize();
        } else {
            dir = Paths.get(System.getProperty("user.home"), ".cosme", "data").toAbsolutePath().normalize();
            source = "默认值";
        }

        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            // 只提示，不阻断启动：有些只读场景下仍然可以跑，写入失败时各模块自己会报错。
            System.err.println("[数据] 无法创建数据目录 " + dir + "：" + e.getMessage());
        }

        System.out.println("[数据] 目录：" + dir + "（来源：" + source + "）");
        System.out.println("[数据] 可用 -D" + PROPERTY + " 或环境变量 " + ENV + " 指向其它目录");
        return dir;
    }
}
