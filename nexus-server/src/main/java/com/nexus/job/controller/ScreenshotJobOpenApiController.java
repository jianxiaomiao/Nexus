package com.nexus.job.controller;

import com.nexus.common.web.ApiResponse;
import com.nexus.job.dto.CreateScreenshotJobRequest;
import com.nexus.job.dto.CreateScreenshotJobResponse;
import com.nexus.job.dto.ScreenshotJobResponse;
import com.nexus.job.service.ScreenshotJobService;
import com.nexus.openapi.web.MachineIdentityResolver;
import com.nexus.usage.ApiCode;
import com.nexus.usage.web.UsageApi;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/v1/web/screenshot-jobs")
public class ScreenshotJobOpenApiController {
    private final MachineIdentityResolver identities;
    private final ScreenshotJobService service;

    public ScreenshotJobOpenApiController(MachineIdentityResolver identities,
                                          ScreenshotJobService service) {
        this.identities = identities;
        this.service = service;
    }

    @UsageApi(ApiCode.WEB_SCREENSHOT)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<CreateScreenshotJobResponse>> submit(
            HttpServletRequest request,
            @Valid @RequestBody CreateScreenshotJobRequest body) {
        var job = service.submit(identities.require(request), body.url());
        URI location = URI.create("/v1/web/screenshot-jobs/" + job.getJobId());
        return ResponseEntity.created(location).body(new ApiResponse<>(
                "SUCCESS", "截图任务已创建", CreateScreenshotJobResponse.from(job)));
    }

    @GetMapping(value = "/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ScreenshotJobResponse> getJob(HttpServletRequest request,
                                                     @PathVariable String jobId) {
        var job = service.getJob(identities.require(request), jobId);
        return new ApiResponse<>("SUCCESS", "截图任务查询成功", ScreenshotJobResponse.from(job));
    }

    @GetMapping(value = "/{jobId}/result", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getResult(HttpServletRequest request,
                                            @PathVariable String jobId) {
        byte[] png = service.getResult(identities.require(request), jobId);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .contentLength(png.length)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"screenshot-" + jobId + ".png\"")
                .body(png);
    }
}
