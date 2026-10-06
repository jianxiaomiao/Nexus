package com.nexus.shortlink.controller;

import com.nexus.shortlink.service.ShortLinkRedirectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/s")
public class ShortLinkRedirectController {
    private final ShortLinkRedirectService redirectService;

    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(redirectService.resolveTarget(shortCode))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }
}
