package com.nexus.auth.token;

public record AccessTokenIdentity(long userId, long sessionId) {
}
