package com.nexus.auth.websession;

import com.nexus.auth.exception.InvalidRefreshTokenException;
import com.nexus.auth.service.LogoutDatabase;
import com.nexus.auth.service.LogoutRetry;
import com.nexus.auth.token.AccessTokenIdentity;
import com.nexus.auth.token.RefreshTokenGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class WebLogoutService {
    private final RefreshTokenGenerator generator;
    private final LogoutDatabase database;
    private final LogoutRetry retry;

    public void logout(String token, String expectedSessionId) {
        String hash = generator.hash(token);
        AtomicReference<AccessTokenIdentity> original = new AtomicReference<>();
        retry.execute(() -> {
            if (original.get() == null) {
                AccessTokenIdentity identity = database.locateRefresh(hash);
                if (identity == null || (expectedSessionId != null && !expectedSessionId.equals(Long.toString(identity.sessionId())))) {
                    throw new InvalidRefreshTokenException();
                }
                original.set(identity);
            }
            database.revokeRefresh(original.get(), hash);
        });
    }
}
