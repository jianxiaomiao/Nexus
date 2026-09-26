package com.nexus.user.mapper;

import com.nexus.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class UserMapperIntegrationTests {

    @Autowired
    private UserMapper userMapper;

    @Test
    void insertShouldPersistUserAndApplyDatabaseDefaults() {
        // Arrange: prepare the input for the behavior under test.
        User user = new User();
        user.setEmail("mapper-test-" + UUID.randomUUID() + "@example.com");
        user.setDisplayName("Mapper Test User");
        user.setPasswordHash("not-a-real-password-hash");

        // Act: execute the Mapper operation.
        int affectedRows = userMapper.insert(user);

        // Assert: verify the insert result and the generated primary key.
        assertEquals(1, affectedRows);
        assertNotNull(user.getId());

        // Re-query because database-generated defaults are not necessarily
        // written back into the original Java object after an INSERT.
        User persistedUser = userMapper.selectById(user.getId());

        assertNotNull(persistedUser);
        assertEquals(user.getEmail(), persistedUser.getEmail());
        assertEquals(user.getDisplayName(), persistedUser.getDisplayName());
        assertEquals(user.getPasswordHash(), persistedUser.getPasswordHash());
        assertNull(persistedUser.getEmailVerifiedAt());
        assertEquals(0, persistedUser.getStatus());
        assertEquals(0, persistedUser.getIsDeleted());
        assertNull(persistedUser.getDeletedAt());
        assertNotNull(persistedUser.getCreatedAt());
        assertNotNull(persistedUser.getUpdatedAt());
    }

    @Test
    void insertShouldRejectDuplicateEmail() {
        // Arrange
        String email = "mapper-test-" + UUID.randomUUID() + "@example.com";

        User firstUser = new User();
        firstUser.setEmail(email);
        firstUser.setDisplayName("First User");
        firstUser.setPasswordHash("not-a-real-password-hash");

        User duplicateUser = new User();
        duplicateUser.setEmail(email);
        duplicateUser.setDisplayName("Duplicate User");
        duplicateUser.setPasswordHash("not-a-real-password-hash");

        // Act
        int affectedRows = userMapper.insert(firstUser);

        // Assert
        assertEquals(1, affectedRows);
        assertNotNull(firstUser.getId());

        assertThrows(
                DuplicateKeyException.class,
                () -> userMapper.insert(duplicateUser)
        );
    }
}
