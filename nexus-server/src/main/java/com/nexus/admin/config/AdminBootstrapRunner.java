package com.nexus.admin.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final String bootstrapEmail;
    private final String bootstrapPassword;

    public AdminBootstrapRunner(
            UserMapper userMapper,
            PasswordEncoder passwordEncoder,
            Clock clock,
            @Value("${nexus.admin.bootstrap.email:}") String bootstrapEmail,
            @Value("${nexus.admin.bootstrap.password:}") String bootstrapPassword) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.bootstrapEmail = bootstrapEmail;
        this.bootstrapPassword = bootstrapPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (bootstrapEmail == null || bootstrapEmail.isBlank()
                || bootstrapPassword == null || bootstrapPassword.isBlank()) {
            return;
        }
        long adminCount = userMapper.selectCount(Wrappers.<User>lambdaQuery()
                .eq(User::getRole, "ADMIN")
                .eq(User::getIsDeleted, 0));
        if (adminCount > 0) {
            return;
        }
        String normalizedEmail = bootstrapEmail.strip().toLowerCase(Locale.ROOT);
        User existing = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getEmail, normalizedEmail));
        if (existing != null) {
            log.warn("Bootstrap admin skipped because email already exists: {}", normalizedEmail);
            return;
        }

        LocalDateTime now = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
        User admin = new User();
        admin.setEmail(normalizedEmail);
        admin.setDisplayName("Nexus Admin");
        admin.setPasswordHash(passwordEncoder.encode(bootstrapPassword));
        admin.setEmailVerifiedAt(now);
        admin.setRole("ADMIN");
        admin.setStatus(0);
        admin.setIsDeleted(0);
        admin.setCreatedAt(now);
        admin.setUpdatedAt(now);
        userMapper.insert(admin);
        log.info("Initialized bootstrap admin account: {}", normalizedEmail);
    }
}
