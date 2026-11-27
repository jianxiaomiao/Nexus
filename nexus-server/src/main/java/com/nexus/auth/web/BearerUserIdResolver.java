package com.nexus.auth.web;

import com.nexus.auth.entity.AuthSession;
import com.nexus.auth.exception.AccountForbiddenException;
import com.nexus.auth.exception.AdminForbiddenException;
import com.nexus.auth.exception.InvalidAccessTokenException;
import com.nexus.auth.mapper.AuthSessionMapper;
import com.nexus.auth.token.AccessTokenIdentity;
import com.nexus.auth.token.AccessTokenVerifier;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneOffset;

@Component
@RequiredArgsConstructor
public class BearerUserIdResolver {
    private final AccessTokenVerifier verifier;
    private final UserMapper userMapper;
    private final AuthSessionMapper sessions;
    private final Clock clock;

    public long resolve(String authorization) {
        return resolveIdentity(authorization).userId();
    }

    public AccessTokenIdentity resolveIdentity(String authorization) {
        AccessTokenIdentity identity = verifier.verify(authorization, false);
        requireActive(identity);
        return identity;
    }

    public long resolveAdmin(String authorization) {
        AccessTokenIdentity identity = verifier.verify(authorization, false);
        User user = requireActiveUser(identity);
        if (!"ADMIN".equals(user.getRole())) {
            throw new AdminForbiddenException();
        }
        return user.getId();
    }

    public User resolveActiveUser(String authorization) {
        AccessTokenIdentity identity = verifier.verify(authorization, false);
        return requireActiveUser(identity);
    }

    public void requireActive(AccessTokenIdentity identity) {
        requireActiveUser(identity);
    }

    private User requireActiveUser(AccessTokenIdentity identity) {
        User user = userMapper.selectById(identity.userId());
        if (user == null || Integer.valueOf(1).equals(user.getIsDeleted())) throw new InvalidAccessTokenException();
        if (Integer.valueOf(1).equals(user.getStatus())) throw new AccountForbiddenException();
        AuthSession session = sessions.selectById(identity.sessionId());
        if (session == null || !Long.valueOf(identity.userId()).equals(session.getUserId())
                || session.getRevokedAt() != null
                || !clock.instant().isBefore(session.getExpiresAt().toInstant(ZoneOffset.UTC))) {
            throw new InvalidAccessTokenException();
        }
        return user;
    }
}
