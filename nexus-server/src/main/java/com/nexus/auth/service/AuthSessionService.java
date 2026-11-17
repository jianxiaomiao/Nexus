package com.nexus.auth.service;

import com.nexus.auth.config.AuthSessionProperties;
import com.nexus.auth.dto.AuthTokensResponse;
import com.nexus.auth.entity.*;
import com.nexus.auth.exception.*;
import com.nexus.auth.mapper.*;
import com.nexus.auth.token.*;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.*;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AuthSessionService {
    private final UserMapper users;
    private final AuthSessionMapper sessions;
    private final RefreshTokenMapper refreshTokens;
    private final RefreshTokenGenerator generator;
    private final JwtTokenService jwtTokens;
    private final AuthSessionProperties properties;
    private final AuthSessionTransactions transactions;
    private final Clock clock;

    /** 密码在事务外校验；锁后复核密码摘要，防止等待期间账号凭据已经变更。 */
    public AuthTokensResponse create(long userId, String verifiedPasswordHash) {
        try {
            return transactions.execute(() -> {
                User user = users.selectForUpdate(userId);
                if (deleted(user) || !Objects.equals(verifiedPasswordHash, user.getPasswordHash())) {
                    throw new InvalidCredentialsException();
                }
                if (banned(user)) throw new AccountForbiddenException();
                Instant now = now();
                AuthSession session = new AuthSession();
                session.setUserId(userId);
                session.setCreatedAt(utc(now));
                session.setExpiresAt(utc(now.plus(properties.absoluteTtl()).truncatedTo(ChronoUnit.MILLIS)));
                if (sessions.insert(session) != 1) throw new DataIntegrityViolationException("会话未创建");
                GeneratedRefreshToken rt = generator.generate();
                insertRefresh(session.getId(), rt, now);
                IssuedAccessToken at = jwtTokens.issueAccessToken(userId, session.getId(), expiry(session), now);
                return AuthTokensResponse.from(at, rt.value(), session.getId().toString(), expiry(session));
            });
        } catch (DataAccessException | TransactionException | JwtEncodingException exception) {
            throw new AuthSessionUnavailableException(exception);
        }
    }

    public AuthTokensResponse refresh(String rawToken) { return refreshForWeb(rawToken, null); }

    public AuthTokensResponse refreshForWeb(String rawToken, String expectedSessionId) {
        AuthSessionTransactions.requireNoOuterTransaction();
        String hash = generator.hash(rawToken);
        try {
            // 定位不可变关联；事务中的 FOR UPDATE 重新读取当前状态，避免RR快照误判。
            RefreshToken locatedToken = refreshTokens.selectByHash(hash);
            if (locatedToken == null) throw new InvalidRefreshTokenException();
            AuthSession locatedSession = sessions.selectById(locatedToken.getSessionId());
            if (locatedSession == null || (expectedSessionId != null && !expectedSessionId.equals(locatedSession.getId().toString()))) throw new InvalidRefreshTokenException();
            RefreshOutcome result = transactions.execute(() -> rotate(locatedSession, hash));
            // execute返回表示提交已成功；重放撤销不能因以下异常而回滚。
            return switch (result.status()) {
                case SUCCESS -> AuthTokensResponse.from(result.accessToken(), result.refreshToken(),
                        locatedSession.getId().toString(), expiry(locatedSession));
                case INVALID -> throw new InvalidRefreshTokenException();
                case ACCOUNT_FORBIDDEN -> throw new AccountForbiddenException();
                case REPLAY_REVOKED -> throw new RefreshTokenReplayException();
            };
        } catch (DataAccessException | TransactionException | JwtEncodingException exception) {
            // 不重试RT轮换：提交失败的结果可能未知。
            throw new AuthSessionUnavailableException(exception);
        }
    }

    private RefreshOutcome rotate(AuthSession located, String hash) {
        User user = users.selectForUpdate(located.getUserId());
        if (deleted(user)) return RefreshOutcome.invalid();
        if (banned(user)) return RefreshOutcome.accountForbidden();
        AuthSession session = sessions.selectForUpdate(located.getId());
        if (session == null || !located.getUserId().equals(session.getUserId())) return RefreshOutcome.invalid();
        RefreshToken token = refreshTokens.selectForUpdate(session.getId(), hash);
        Instant now = now();
        if (session.getRevokedAt() != null || !now.isBefore(expiry(session)) || token == null) {
            return RefreshOutcome.invalid();
        }
        if (token.getConsumedAt() != null) {
            if (sessions.revoke(session.getId(), utc(now)) != 1) {
                throw new ConcurrencyFailureException("会话撤销未成功");
            }
            return RefreshOutcome.replayRevoked();
        }
        // JWT NumericDate为秒；不足一个完整有效秒时拒绝，不能返回0秒令牌或越过会话期限。
        if (Duration.between(now, expiry(session).truncatedTo(ChronoUnit.SECONDS)).toSeconds() <= 0) {
            return RefreshOutcome.invalid();
        }
        if (refreshTokens.consume(token.getId(), utc(now)) != 1) {
            throw new ConcurrencyFailureException("刷新凭据消费未成功");
        }
        GeneratedRefreshToken next = generator.generate();
        insertRefresh(session.getId(), next, now);
        IssuedAccessToken at = jwtTokens.issueAccessToken(user.getId(), session.getId(), expiry(session), now);
        return RefreshOutcome.success(at, next.value());
    }

    private void insertRefresh(long sessionId, GeneratedRefreshToken generated, Instant now) {
        RefreshToken token = new RefreshToken();
        token.setSessionId(sessionId);
        token.setTokenHash(generated.tokenHash());
        token.setCreatedAt(utc(now));
        if (refreshTokens.insert(token) != 1) throw new DataIntegrityViolationException("刷新凭据未创建");
    }

    private boolean deleted(User user) { return user == null || Integer.valueOf(1).equals(user.getIsDeleted()); }
    private boolean banned(User user) { return Integer.valueOf(1).equals(user.getStatus()); }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
    private LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private Instant expiry(AuthSession session) { return session.getExpiresAt().toInstant(ZoneOffset.UTC); }
}
