package com.nexus.auth.service;

import com.nexus.auth.dto.CaptchaResponse;
import com.nexus.auth.exception.InvalidCaptchaException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ArithmeticCaptchaServiceTests {

    private static final Pattern EXPR_PATTERN = Pattern.compile("^(\\d+) ([+\\-×÷]) (\\d+) = \\?$");

    @Test
    void generatedExpressionsShouldBeNonNegativeIntegerAndNeverDivideByZero() {
        ArithmeticCaptchaService service = new ArithmeticCaptchaService(Clock.systemUTC(), 180, 5000);
        for (int i = 0; i < 500; i++) {
            ArithmeticCaptchaService.ArithmeticExpression expr = service.nextExpression();
            Matcher matcher = EXPR_PATTERN.matcher(expr.display());
            assertTrue(matcher.matches(), "Invalid display: " + expr.display());
            int a = Integer.parseInt(matcher.group(1));
            String op = matcher.group(2);
            int b = Integer.parseInt(matcher.group(3));
            assertTrue(expr.answer() >= 0, "Answer must be non-negative: " + expr);
            switch (op) {
                case "+" -> assertEquals(a + b, expr.answer());
                case "-" -> {
                    assertTrue(a >= b);
                    assertEquals(a - b, expr.answer());
                }
                case "×" -> assertEquals(a * b, expr.answer());
                case "÷" -> {
                    assertTrue(b >= 2, "Divisor must be >= 2");
                    assertEquals(0, a % b, "Division must be exact");
                    assertEquals(a / b, expr.answer());
                }
                default -> fail("Unexpected operator: " + op);
            }
        }
    }

    @Test
    void generateShouldReturnValidSvgAndVerifyShouldBeOneTimeUse() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-27T06:00:00Z"));
        Clock mutableClock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };

        ArithmeticCaptchaService service = new ArithmeticCaptchaService(mutableClock, 180, 5000);
        CaptchaResponse captcha = service.generate();
        assertNotNull(captcha.captchaId());
        assertEquals(180, captcha.expiresInSeconds());
        assertTrue(captcha.imageDataUrl().startsWith("data:image/svg+xml;base64,"));

        String svg = new String(Base64.getDecoder().decode(
                captcha.imageDataUrl().substring("data:image/svg+xml;base64,".length())), StandardCharsets.UTF_8);
        assertTrue(svg.startsWith("<svg"));
        assertTrue(svg.endsWith("</svg>"));

        String answer = solveFromSvg(svg);

        // 错答一次也会立刻消耗，之后即使答对也会失败
        assertThrows(InvalidCaptchaException.class, () -> service.verifyAndConsume(captcha.captchaId(), "99999"));
        assertThrows(InvalidCaptchaException.class, () -> service.verifyAndConsume(captcha.captchaId(), answer));

        // 正确回答可一次性通过，二次复用失败
        CaptchaResponse second = service.generate();
        String secondAnswer = solveFromSvg(new String(Base64.getDecoder().decode(
                second.imageDataUrl().substring("data:image/svg+xml;base64,".length())), StandardCharsets.UTF_8));
        assertDoesNotThrow(() -> service.verifyAndConsume(second.captchaId(), secondAnswer));
        assertThrows(InvalidCaptchaException.class, () -> service.verifyAndConsume(second.captchaId(), secondAnswer));

        // 超过 180 秒后过期
        CaptchaResponse third = service.generate();
        String thirdAnswer = solveFromSvg(new String(Base64.getDecoder().decode(
                third.imageDataUrl().substring("data:image/svg+xml;base64,".length())), StandardCharsets.UTF_8));
        now.set(now.get().plusSeconds(181));
        assertThrows(InvalidCaptchaException.class, () -> service.verifyAndConsume(third.captchaId(), thirdAnswer));
    }

    @Test
    void capacityLimitShouldEvictOldestEntries() {
        ArithmeticCaptchaService service = new ArithmeticCaptchaService(Clock.systemUTC(), 180, 3);
        service.generate();
        service.generate();
        service.generate();
        service.generate();
        assertEquals(3, service.activeCount());
    }

    static String solveFromSvg(String svg) {
        StringBuilder chars = new StringBuilder();
        Matcher m = Pattern.compile(">([^<]+)</text>").matcher(svg);
        while (m.find()) {
            chars.append(m.group(1));
        }
        Matcher expr = Pattern.compile("^(\\d+)([+\\-×÷])(\\d+)=\\?$").matcher(chars.toString());
        if (!expr.matches()) {
            throw new IllegalStateException("Cannot parse expression from SVG text: " + chars);
        }
        int a = Integer.parseInt(expr.group(1));
        String op = expr.group(2);
        int b = Integer.parseInt(expr.group(3));
        return String.valueOf(switch (op) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "×" -> a * b;
            case "÷" -> a / b;
            default -> throw new IllegalStateException("Unexpected op: " + op);
        });
    }
}
