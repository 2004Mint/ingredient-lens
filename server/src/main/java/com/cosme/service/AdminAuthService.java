package com.cosme.service;

import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理台登录。
 *
 * 管理接口能改视觉服务的地址和 Key，不能裸奔：
 * 密码默认自动生成到 server/data/admin-password.txt，也可以用 ADMIN_PASSWORD 环境变量指定。
 * 登录后发一个 12 小时有效的内存令牌，后端重启即失效。
 */
@Service
public class AdminAuthService {

    private static final long TTL_MS = 12L * 60 * 60 * 1000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path dir = Paths.get(System.getProperty("user.dir"), "data");
    private final Path passwordFile = dir.resolve("admin-password.txt");
    private final Map<String, Long> tokens = new ConcurrentHashMap<>();

    private String password;
    private String passwordSource;

    @PostConstruct
    public void init() {
        String env = System.getenv("ADMIN_PASSWORD");
        if (VisionSettings.notBlank(env)) {
            this.password = env.trim();
            this.passwordSource = "环境变量 ADMIN_PASSWORD";
            System.out.println("[管理台] 密码来源：" + passwordSource);
            return;
        }

        try {
            Files.createDirectories(dir);
            if (Files.exists(passwordFile)) {
                String saved = new String(Files.readAllBytes(passwordFile), StandardCharsets.UTF_8).trim();
                if (!saved.isEmpty()) {
                    this.password = saved;
                    this.passwordSource = passwordFile.toString();
                    System.out.println("[管理台] 密码来源：" + passwordSource);
                    return;
                }
            }
            this.password = generatePassword();
            Files.write(passwordFile, (password + "\n").getBytes(StandardCharsets.UTF_8));
            this.passwordSource = passwordFile.toString();
            System.out.println("[管理台] 已生成管理密码：" + passwordSource);
        } catch (IOException e) {
            this.password = generatePassword();
            this.passwordSource = "内存（密码文件不可写）";
            System.err.println("[管理台] 无法写入密码文件：" + e.getMessage());
            System.err.println("[管理台] 临时管理密码：" + password);
        }
    }

    public String login(String input) {
        if (input == null || password == null) return null;
        byte[] expected = password.getBytes(StandardCharsets.UTF_8);
        byte[] actual = input.trim().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) return null;

        String token = UUID.randomUUID().toString().replace("-", "");
        tokens.put(token, System.currentTimeMillis() + TTL_MS);
        return token;
    }

    public boolean isValid(String token) {
        if (token == null || token.isEmpty()) return false;
        Long expireAt = tokens.get(token);
        if (expireAt == null) return false;
        if (expireAt < System.currentTimeMillis()) {
            tokens.remove(token);
            return false;
        }
        return true;
    }

    public void logout(String token) {
        if (token != null) tokens.remove(token);
    }

    public String passwordSource() {
        return passwordSource;
    }

    public long ttlSeconds() {
        return TTL_MS / 1000;
    }

    private String generatePassword() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
