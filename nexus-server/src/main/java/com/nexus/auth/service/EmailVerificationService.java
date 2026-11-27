package com.nexus.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.auth.entity.EmailVerificationCode;
import com.nexus.auth.exception.EmailAlreadyRegisteredException;
import com.nexus.auth.exception.EmailCodeRateLimitedException;
import com.nexus.auth.exception.InvalidEmailVerificationCodeException;
import com.nexus.auth.mapper.EmailVerificationCodeMapper;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class EmailVerificationService {

    public static final String PURPOSE_REGISTER = "REGISTER";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmailVerificationCodeMapper codeMapper;
    private final UserMapper userMapper;
    private final EmailSender emailSender;
    private final EmailVerificationAttemptService attemptService;
    private final Clock clock;
    private final int ttlMinutes;
    private final int cooldownSeconds;
    private final int maxAttempts;
    private final String pepper;

    public EmailVerificationService(
            EmailVerificationCodeMapper codeMapper,
            UserMapper userMapper,
            EmailSender emailSender,
            EmailVerificationAttemptService attemptService,
            Clock clock,
            @Value("${nexus.auth.email-code.ttl-minutes:10}") int ttlMinutes,
            @Value("${nexus.auth.email-code.cooldown-seconds:60}") int cooldownSeconds,
            @Value("${nexus.auth.email-code.max-attempts:5}") int maxAttempts,
            @Value("${nexus.auth.email-code.pepper:nexus-dev-email-code-pepper}") String pepper) {
        this.codeMapper = codeMapper;
        this.userMapper = userMapper;
        this.emailSender = emailSender;
        this.attemptService = attemptService;
        this.clock = clock;
        this.ttlMinutes = ttlMinutes;
        this.cooldownSeconds = cooldownSeconds;
        this.maxAttempts = maxAttempts;
        this.pepper = pepper;
    }

    @Transactional
    public void sendRegisterCode(String rawEmail) {
        String normalizedEmail = rawEmail.strip().toLowerCase(Locale.ROOT);
        long existingUserCount = userMapper.selectCount(Wrappers.<User>lambdaQuery()
                .eq(User::getEmail, normalizedEmail));
        if (existingUserCount > 0) {
            throw new EmailAlreadyRegisteredException(new IllegalStateException("email already exists"));
        }

        LocalDateTime now = LocalDateTime.ofInstant(
                clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);

        EmailVerificationCode latest = codeMapper.selectOne(Wrappers.<EmailVerificationCode>lambdaQuery()
                .eq(EmailVerificationCode::getEmail, normalizedEmail)
                .eq(EmailVerificationCode::getPurpose, PURPOSE_REGISTER)
                .orderByDesc(EmailVerificationCode::getId)
                .last("LIMIT 1"));
        if (latest != null && Duration.between(latest.getCreatedAt(), now).getSeconds() < cooldownSeconds) {
            throw new EmailCodeRateLimitedException();
        }

        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        EmailVerificationCode record = new EmailVerificationCode();
        record.setEmail(normalizedEmail);
        record.setPurpose(PURPOSE_REGISTER);
        record.setCodeHash(hashCode(normalizedEmail, PURPOSE_REGISTER, code));
        record.setExpiresAt(now.plusMinutes(ttlMinutes));
        record.setAttemptCount(0);
        record.setCreatedAt(now);
        codeMapper.insert(record);

        emailSender.sendVerificationCode(normalizedEmail, code, ttlMinutes);
    }

    @Transactional
    public void verifyAndConsumeRegisterCode(String normalizedEmail, String rawCode, LocalDateTime now) {
        if (rawCode == null || rawCode.isBlank()) {
            throw new InvalidEmailVerificationCodeException();
        }
        String code = rawCode.strip();
        EmailVerificationCode latest = codeMapper.selectOne(Wrappers.<EmailVerificationCode>lambdaQuery()
                .eq(EmailVerificationCode::getEmail, normalizedEmail)
                .eq(EmailVerificationCode::getPurpose, PURPOSE_REGISTER)
                .isNull(EmailVerificationCode::getConsumedAt)
                .orderByDesc(EmailVerificationCode::getId)
                .last("LIMIT 1"));
        if (latest == null
                || !now.isBefore(latest.getExpiresAt())
                || latest.getAttemptCount() >= maxAttempts) {
            throw new InvalidEmailVerificationCodeException();
        }

        String expectedHash = latest.getCodeHash();
        String actualHash = hashCode(normalizedEmail, PURPOSE_REGISTER, code);
        if (!MessageDigest.isEqual(
                expectedHash.getBytes(StandardCharsets.UTF_8),
                actualHash.getBytes(StandardCharsets.UTF_8))) {
            attemptService.recordFailedAttempt(latest.getId());
            throw new InvalidEmailVerificationCodeException();
        }

        int updated = codeMapper.update(null, Wrappers.<EmailVerificationCode>lambdaUpdate()
                .eq(EmailVerificationCode::getId, latest.getId())
                .isNull(EmailVerificationCode::getConsumedAt)
                .lt(EmailVerificationCode::getAttemptCount, maxAttempts)
                .set(EmailVerificationCode::getConsumedAt, now));
        if (updated == 0) {
            throw new InvalidEmailVerificationCodeException();
        }
    }

    private String hashCode(String email, String purpose, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((pepper + ":" + email + ":" + purpose + ":" + code)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
