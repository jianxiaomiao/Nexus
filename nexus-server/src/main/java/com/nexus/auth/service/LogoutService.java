package com.nexus.auth.service;

import com.nexus.auth.token.AccessTokenIdentity;
import com.nexus.auth.token.AccessTokenVerifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LogoutService {
    private final AccessTokenVerifier verifier;
    private final LogoutDatabase database;
    private final LogoutRetry retry;

    public void logout(String authorization) {
        AccessTokenIdentity original = verifier.verify(authorization, true);
        retry.execute(() -> database.revoke(original));
    }
}
