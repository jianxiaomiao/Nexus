package com.nexus.auth.token;

import com.nexus.auth.config.*;
import com.nexus.auth.exception.InvalidAccessTokenException;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AccessTokenVerifierTests {
    static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final JwtConfig config = new JwtConfig();
    final JwtProperties properties = new JwtProperties("nexus-test", Duration.ofMinutes(30),
            Base64.getEncoder().encodeToString(new byte[32]));
    final JwtEncoder encoder = config.jwtEncoder(config.jwtSigningKey(properties));
    final AccessTokenVerifier verifier = new AccessTokenVerifier(
            config.jwtDecoder(config.jwtSigningKey(properties), properties, clock),
            config.logoutJwtDecoder(config.jwtSigningKey(properties), properties, clock), clock);

    @Test void signedIdentityContainsBothUserAndSession() {
        assertEquals(new AccessTokenIdentity(42, 7), verifier.verify("Bearer " + token("nexus-test", "42", "7", NOW.minusSeconds(1), NOW.plusSeconds(30), null), false));
    }
    @Test void expiredTokenIsAcceptedOnlyForLogout() {
        String header = "Bearer " + token("nexus-test", "42", "7", NOW.minusSeconds(120), NOW.minusSeconds(1), null);
        assertThrows(InvalidAccessTokenException.class, () -> verifier.verify(header, false));
        assertEquals(new AccessTokenIdentity(42, 7), verifier.verify(header, true));
    }
    @Test void exactExpiryInstantIsAlreadyInvalidForManagement() {
        String header = "Bearer " + token("nexus-test", "42", "7", NOW.minusSeconds(120), NOW, null);
        assertThrows(InvalidAccessTokenException.class, () -> verifier.verify(header, false));
    }
    @Test void logoutStillRejectsWrongIssuerFutureIssueTimeAndNotBefore() {
        for (String token : List.of(token("other", "42", "7", NOW.minusSeconds(1), NOW.plusSeconds(30), null),
                token("nexus-test", "42", "7", NOW.plusSeconds(1), NOW.plusSeconds(30), null),
                token("nexus-test", "42", "7", NOW.minusSeconds(1), NOW.plusSeconds(30), NOW.plusSeconds(1)))) {
            assertThrows(InvalidAccessTokenException.class, () -> verifier.verify("Bearer " + token, true));
        }
    }
    @Test void missingNumericOrMalformedSessionIdsCannotFallBackToSubject() {
        for (Object sid : Arrays.asList(null, 7, "0", "-1", "01", " 7", "9223372036854775808")) {
            String header = "Bearer " + token("nexus-test", "42", sid, NOW.minusSeconds(1), NOW.plusSeconds(30), null);
            assertThrows(InvalidAccessTokenException.class, () -> verifier.verify(header, true));
        }
    }
    @Test void missingExpiryOrIssueTimeCannotBeUsedForLogout() {
        for (String token : List.of(token("nexus-test", "42", "7", null, NOW.plusSeconds(30), null),
                token("nexus-test", "42", "7", NOW.minusSeconds(1), null, null))) {
            assertThrows(InvalidAccessTokenException.class, () -> verifier.verify("Bearer " + token, true));
        }
    }
    @Test void malformedOrTamperedBearerValuesAreRejected() {
        String valid = token("nexus-test", "42", "7", NOW.minusSeconds(1), NOW.plusSeconds(30), null);
        String[] parts = valid.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + "A".repeat(43);
        for (String header : Arrays.asList(null, "", "Basic " + valid, "Bearer ", "Bearer " + valid + " extra", "Bearer " + tampered)) {
            assertThrows(InvalidAccessTokenException.class, () -> verifier.verify(header, true));
        }
    }
    String token(String issuer, String subject, Object sid, Instant issued, Instant expiry, Instant nbf) {
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder().issuer(issuer).subject(subject);
        if (sid != null) builder.claim("sid", sid);
        if (issued != null) builder.issuedAt(issued);
        if (expiry != null) builder.expiresAt(expiry);
        if (nbf != null) builder.notBefore(nbf);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), builder.build())).getTokenValue();
    }
}
