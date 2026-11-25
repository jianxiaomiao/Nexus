package com.nexus.shortlink.service;

import com.nexus.shortlink.exception.ShortLinkExpiredException;
import com.nexus.shortlink.exception.ShortLinkNotFoundException;
import com.nexus.shortlink.mapper.ShortLinkMapper;
import com.nexus.shortlink.mapper.ShortLinkMapper.RedirectTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShortLinkRedirectServiceTests {
    private static final String SHORT_CODE = "FreshCode123";
    private static final String URL = "https://www.douyin.com/video/1";
    private static final RedirectTarget LINK = new RedirectTarget(URL, LocalDateTime.parse("2026-10-06T00:00:00"));

    @Mock ShortLinkMapper shortLinkMapper;
    @Mock Clock clock;
    @InjectMocks ShortLinkRedirectService service;

    @Test
    void resolvesWithASingleMapperQueryBeforeExpiry() {
        when(shortLinkMapper.findActiveRedirectTarget(SHORT_CODE)).thenReturn(LINK);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-05T23:59:59.999Z"));

        assertEquals(URL, service.resolveTarget(SHORT_CODE).toString());
        verify(shortLinkMapper).findActiveRedirectTarget(SHORT_CODE);
        verifyNoMoreInteractions(shortLinkMapper);
    }

    @Test
    void expiresExactlyAtExpiresAt() {
        when(shortLinkMapper.findActiveRedirectTarget(SHORT_CODE)).thenReturn(LINK);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-06T00:00:00Z"));
        assertThrows(ShortLinkExpiredException.class, () -> service.resolveTarget(SHORT_CODE));
    }

    @Test
    void missingActiveTargetIsNotFoundBeforeExpiryCheck() {
        assertThrows(ShortLinkNotFoundException.class, () -> service.resolveTarget(SHORT_CODE));
        verifyNoInteractions(clock);
    }
}
