package com.nexus.auth.service;

import com.nexus.auth.dto.CaptchaResponse;
import com.nexus.auth.exception.InvalidCaptchaException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ArithmeticCaptchaService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String[] TEXT_COLORS = {"#1f2421", "#247b70", "#3a5a4f", "#4a5953", "#2b4d46"};
    private static final String[] NOISE_COLORS = {"#9db4aa", "#b6c5be", "#c9d4ce", "#89a89d"};

    private final Clock clock;
    private final int ttlSeconds;
    private final int maxEntries;
    private final ConcurrentHashMap<String, CaptchaChallenge> challenges = new ConcurrentHashMap<>();

    @Autowired
    public ArithmeticCaptchaService(
            Clock clock,
            @Value("${nexus.auth.captcha.ttl-seconds:180}") int ttlSeconds,
            @Value("${nexus.auth.captcha.max-entries:5000}") int maxEntries) {
        this.clock = clock;
        this.ttlSeconds = ttlSeconds;
        this.maxEntries = maxEntries;
    }

    public CaptchaResponse generate() {
        Instant now = clock.instant();
        cleanupAndEnforceCapacity(now);

        ArithmeticExpression expr = nextExpression();
        String captchaId = UUID.randomUUID().toString().replace("-", "");
        challenges.put(captchaId, new CaptchaChallenge(String.valueOf(expr.answer()), now.plusSeconds(ttlSeconds)));

        String svg = renderSvg(expr.display());
        String dataUrl = "data:image/svg+xml;base64,"
                + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
        return new CaptchaResponse(captchaId, dataUrl, ttlSeconds);
    }

    public void verifyAndConsume(String captchaId, String rawAnswer) {
        if (captchaId == null || captchaId.isBlank() || rawAnswer == null || rawAnswer.isBlank()) {
            throw new InvalidCaptchaException();
        }
        // 无论对错，原子删除实现一次性消费，防止同一 captchaId 被暴力重试
        CaptchaChallenge challenge = challenges.remove(captchaId.strip());
        if (challenge == null || clock.instant().isAfter(challenge.expiresAt())) {
            throw new InvalidCaptchaException();
        }
        if (!challenge.expectedAnswer().equals(rawAnswer.strip())) {
            throw new InvalidCaptchaException();
        }
    }

    ArithmeticExpression nextExpression() {
        int op = RANDOM.nextInt(4);
        return switch (op) {
            case 0 -> {
                int a = 1 + RANDOM.nextInt(20);
                int b = 1 + RANDOM.nextInt(20);
                yield new ArithmeticExpression(a + " + " + b + " = ?", a + b);
            }
            case 1 -> {
                int a = 5 + RANDOM.nextInt(26);
                int b = 1 + RANDOM.nextInt(a);
                yield new ArithmeticExpression(a + " - " + b + " = ?", a - b);
            }
            case 2 -> {
                int a = 2 + RANDOM.nextInt(8);
                int b = 2 + RANDOM.nextInt(8);
                yield new ArithmeticExpression(a + " × " + b + " = ?", a * b);
            }
            default -> {
                int b = 2 + RANDOM.nextInt(8);
                int q = 1 + RANDOM.nextInt(9);
                int a = b * q;
                yield new ArithmeticExpression(a + " ÷ " + b + " = ?", q);
            }
        };
    }

    String renderSvg(String text) {
        int width = 148;
        int height = 44;
        StringBuilder sb = new StringBuilder(1024);
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width)
                .append("\" height=\"").append(height)
                .append("\" viewBox=\"0 0 ").append(width).append(' ').append(height).append("\">");
        sb.append("<rect width=\"100%\" height=\"100%\" rx=\"8\" fill=\"#f5f1e6\"/>");

        for (int i = 0; i < 4; i++) {
            int x1 = RANDOM.nextInt(width / 2);
            int y1 = RANDOM.nextInt(height);
            int x2 = width / 2 + RANDOM.nextInt(width / 2);
            int y2 = RANDOM.nextInt(height);
            String color = NOISE_COLORS[RANDOM.nextInt(NOISE_COLORS.length)];
            sb.append("<line x1=\"").append(x1).append("\" y1=\"").append(y1)
                    .append("\" x2=\"").append(x2).append("\" y2=\"").append(y2)
                    .append("\" stroke=\"").append(color)
                    .append("\" stroke-width=\"1.2\" opacity=\"0.75\"/>");
        }

        for (int i = 0; i < 18; i++) {
            int cx = 4 + RANDOM.nextInt(width - 8);
            int cy = 4 + RANDOM.nextInt(height - 8);
            String color = NOISE_COLORS[RANDOM.nextInt(NOISE_COLORS.length)];
            sb.append("<circle cx=\"").append(cx).append("\" cy=\"").append(cy)
                    .append("\" r=\"1.2\" fill=\"").append(color).append("\" opacity=\"0.8\"/>");
        }

        int charCount = text.length();
        double step = (width - 20.0) / Math.max(charCount, 1);
        for (int i = 0; i < charCount; i++) {
            char ch = text.charAt(i);
            if (ch == ' ') continue;
            int x = (int) Math.round(12 + i * step);
            int y = 27 + RANDOM.nextInt(6) - 2;
            int angle = RANDOM.nextInt(21) - 10;
            String color = TEXT_COLORS[RANDOM.nextInt(TEXT_COLORS.length)];
            sb.append("<text x=\"").append(x).append("\" y=\"").append(y)
                    .append("\" fill=\"").append(color)
                    .append("\" font-family=\"ui-monospace,monospace\" font-size=\"20\" font-weight=\"700\" transform=\"rotate(")
                    .append(angle).append(' ').append(x).append(' ').append(y).append(")\">")
                    .append(escapeXml(ch)).append("</text>");
        }

        sb.append("</svg>");
        return sb.toString();
    }

    private void cleanupAndEnforceCapacity(Instant now) {
        challenges.entrySet().removeIf(entry -> now.isAfter(entry.getValue().expiresAt()));
        while (challenges.size() >= maxEntries) {
            String oldestKey = challenges.entrySet().stream()
                    .min(Comparator.comparing(e -> e.getValue().expiresAt()))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldestKey == null) break;
            challenges.remove(oldestKey);
        }
    }

    private static String escapeXml(char ch) {
        return switch (ch) {
            case '&' -> "&amp;";
            case '<' -> "&lt;";
            case '>' -> "&gt;";
            default -> String.valueOf(ch);
        };
    }

    int activeCount() {
        return challenges.size();
    }

    record ArithmeticExpression(String display, int answer) {}

    private record CaptchaChallenge(String expectedAnswer, Instant expiresAt) {}
}
