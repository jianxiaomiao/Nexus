package com.nexus.auth.token;

import com.nexus.auth.exception.InvalidAccessTokenException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import java.time.Clock;

@Component
public class AccessTokenVerifier {
    private final JwtDecoder decoder;
    private final JwtDecoder logoutDecoder;
    private final Clock clock;

    public AccessTokenVerifier(JwtDecoder decoder, @Qualifier("logoutJwtDecoder") JwtDecoder logoutDecoder, Clock clock) {
        this.decoder = decoder;
        this.logoutDecoder = logoutDecoder;
        this.clock = clock;
    }

    public AccessTokenIdentity verify(String authorization, boolean forLogout) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new InvalidAccessTokenException();
        }
        String token = authorization.substring(7);
        if (token.isBlank() || token.chars().anyMatch(Character::isWhitespace)) throw new InvalidAccessTokenException();
        try {
            Jwt jwt = (forLogout ? logoutDecoder : decoder).decode(token);
            if (jwt.getIssuedAt() == null || jwt.getExpiresAt() == null
                    || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    || jwt.getIssuedAt().isAfter(clock.instant())
                    || (!forLogout && !clock.instant().isBefore(jwt.getExpiresAt()))) {
                throw new InvalidAccessTokenException();
            }
            Object sid = jwt.getClaims().get("sid");
            if (!(sid instanceof String text)) throw new InvalidAccessTokenException();
            return new AccessTokenIdentity(positiveId(jwt.getSubject()), positiveId(text));
        } catch (JwtException | IllegalArgumentException exception) {
            throw new InvalidAccessTokenException();
        }
    }

    private long positiveId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new InvalidAccessTokenException();
        long id = Long.parseLong(value);
        if (id <= 0) throw new InvalidAccessTokenException();
        return id;
    }
}
