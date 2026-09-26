package com.nexus.application.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.application.entity.Application;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class ApplicationMapperIntegrationTests {

    private static final String TEST_JWT_SECRET = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)
    );

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("nexus.auth.jwt.secret", () -> TEST_JWT_SECRET);
    }

    @Autowired
    private ApplicationMapper applicationMapper;

    @Autowired
    private UserMapper userMapper;

    @Test
    void insertQueryUpdateAndSoftDeleteApplication() {
        User owner = insertUser();
        Application application = insertApplication(owner.getId(), "test-app");

        Application found = applicationMapper.selectOne(
                Wrappers.<Application>lambdaQuery()
                        .eq(Application::getOwnerUserId, owner.getId())
                        .eq(Application::getName, "test-app")
        );

        assertNotNull(found);
        assertAll(
                () -> assertEquals(application.getId(), found.getId()),
                () -> assertEquals(owner.getId(), found.getOwnerUserId()),
                () -> assertEquals(0, found.getStatus()),
                () -> assertEquals(0, found.getIsDeleted()),
                () -> assertNull(found.getDeletedAt()),
                () -> assertNotNull(found.getCreatedAt()),
                () -> assertNotNull(found.getUpdatedAt())
        );

        application.setName("renamed-app");
        application.setStatus(1);
        assertEquals(1, applicationMapper.updateById(application));

        Application updated = applicationMapper.selectById(application.getId());
        assertEquals("renamed-app", updated.getName());
        assertEquals(1, updated.getStatus());

        application.setIsDeleted(1);
        application.setDeletedAt(LocalDateTime.now());
        assertEquals(1, applicationMapper.updateById(application));

        Application deleted = applicationMapper.selectById(application.getId());
        assertNotNull(deleted);
        assertEquals(1, deleted.getIsDeleted());
        assertNotNull(deleted.getDeletedAt());
    }

    @Test
    void insertShouldRejectUnknownOwner() {
        Application application = new Application();
        application.setOwnerUserId(Long.MAX_VALUE);
        application.setName("orphan-app");

        assertThrows(
                DataIntegrityViolationException.class,
                () -> applicationMapper.insert(application)
        );
    }

    @Test
    void selectShouldReturnNullWhenApplicationDoesNotExist() {
        assertNull(applicationMapper.selectById(Long.MAX_VALUE));
    }

    @Test
    void insertShouldRejectDuplicateNameForSameOwner() {
        User owner = insertUser();
        insertApplication(owner.getId(), "same-name");

        Application duplicate = new Application();
        duplicate.setOwnerUserId(owner.getId());
        duplicate.setName("same-name");

        assertThrows(
                DuplicateKeyException.class,
                () -> applicationMapper.insert(duplicate)
        );
    }

    @Test
    void insertShouldAllowSameNameForDifferentOwners() {
        User firstOwner = insertUser();
        User secondOwner = insertUser();

        Application first = insertApplication(firstOwner.getId(), "shared-name");
        Application second = insertApplication(secondOwner.getId(), "shared-name");

        assertNotEquals(first.getId(), second.getId());
        assertEquals(firstOwner.getId(), first.getOwnerUserId());
        assertEquals(secondOwner.getId(), second.getOwnerUserId());
    }

    @Test
    void deletedNameShouldBeReusableMoreThanOnce() {
        User owner = insertUser();
        String name = "reusable-" + UUID.randomUUID();

        Application first = insertApplication(owner.getId(), name);
        softDelete(first);
        Application second = insertApplication(owner.getId(), name);
        softDelete(second);
        Application third = insertApplication(owner.getId(), name);

        assertNotEquals(first.getId(), second.getId());
        assertNotEquals(second.getId(), third.getId());
        assertEquals(3L, applicationMapper.selectCount(
                Wrappers.<Application>lambdaQuery()
                        .eq(Application::getOwnerUserId, owner.getId())
                        .eq(Application::getName, name)
        ));
    }

    private void softDelete(Application application) {
        application.setIsDeleted(1);
        application.setDeletedAt(LocalDateTime.now());
        assertEquals(1, applicationMapper.updateById(application));
    }

    private User insertUser() {
        User user = new User();
        user.setEmail("application-mapper-" + UUID.randomUUID() + "@example.com");
        user.setDisplayName("Application Mapper Test User");
        user.setPasswordHash("test-only-password-hash");
        assertEquals(1, userMapper.insert(user));
        assertNotNull(user.getId());
        return user;
    }

    private Application insertApplication(long ownerUserId, String name) {
        Application application = new Application();
        application.setOwnerUserId(ownerUserId);
        application.setName(name);
        assertEquals(1, applicationMapper.insert(application));
        assertNotNull(application.getId());
        return application;
    }
}
