package com.nexus.screenshot.controller;

import com.nexus.openapi.web.MachineIdentityResolver;
import com.nexus.screenshot.dto.ScreenshotRequest;
import com.nexus.screenshot.service.ScreenshotService;
import com.nexus.usage.ApiCode;
import com.nexus.usage.web.UsageApi;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/web")
public class ScreenshotOpenApiController {
    private final MachineIdentityResolver identities;
    private final ScreenshotService service;

    public ScreenshotOpenApiController(MachineIdentityResolver identities, ScreenshotService service) { this.identities = identities; this.service = service; }

    @UsageApi(ApiCode.WEB_SCREENSHOT)
    @PostMapping(value = "/screenshot", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> capture(HttpServletRequest request, @Valid @RequestBody ScreenshotRequest body) {
        var identity = identities.require(request);
        var result = service.capture(identity, body.url());
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .header("Cache-Control", "no-store").header("Content-Disposition", "inline; filename=\"screenshot.png\"")
                .header("X-Nexus-Blocked-Resources", Integer.toString(result.blockedResources()))
                .header("X-Nexus-Resource-Limit-Reached", Boolean.toString(result.resourceLimitReached()))
                .body(result.png());
    }
}
