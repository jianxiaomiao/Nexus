package com.nexus.job.controller;

import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.common.web.GlobalExceptionHandler;
import com.nexus.job.entity.ScreenshotJob;
import com.nexus.job.exception.ScreenshotJobFailedException;
import com.nexus.job.exception.ScreenshotJobNotFoundException;
import com.nexus.job.exception.ScreenshotJobNotReadyException;
import com.nexus.job.exception.ScreenshotJobQueueFullException;
import com.nexus.job.exception.ScreenshotJobResultExpiredException;
import com.nexus.job.exception.ScreenshotJobSubmissionUnavailableException;
import com.nexus.job.model.ScreenshotJobStatus;
import com.nexus.job.service.ScreenshotJobService;
import com.nexus.job.store.ScreenshotResultStoreException;
import com.nexus.openapi.web.MachineIdentityResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ScreenshotJobOpenApiControllerTests {
    private static final ApiKeyIdentity IDENTITY = new ApiKeyIdentity(7L, 3L);
    private static final String JOB_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String URL = "https://blog.csdn.net/article";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 23, 6, 30);

    private final ScreenshotJobService service = mock(ScreenshotJobService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new ScreenshotJobOpenApiController(new MachineIdentityResolver(), service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void submissionReturns201LocationAndPublicQueuedState() throws Exception {
        when(service.submit(IDENTITY, URL)).thenReturn(job(ScreenshotJobStatus.QUEUED));

        mvc.perform(post("/v1/web/screenshot-jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + URL + "\"}")
                        .requestAttr(MachineIdentityResolver.ATTRIBUTE_NAME, IDENTITY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/v1/web/screenshot-jobs/" + JOB_ID))
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.jobId").value(JOB_ID))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.attemptCount").value(0))
                .andExpect(jsonPath("$.data.createdAt").value("2026-10-23T06:30:00Z"))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-10-23T06:30:00Z"));
    }

    @Test
    void invalidBodyAndMissingMachineIdentityNeverSubmit() throws Exception {
        mvc.perform(post("/v1/web/screenshot-jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"\"}")
                        .requestAttr(MachineIdentityResolver.ATTRIBUTE_NAME, IDENTITY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(post("/v1/web/screenshot-jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + URL + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_API_KEY_CREDENTIAL"));

        verifyNoInteractions(service);
    }

    @Test
    void queueCapacityAndDatabaseAvailabilityHaveDistinct503Codes() throws Exception {
        when(service.submit(IDENTITY, URL))
                .thenThrow(new ScreenshotJobQueueFullException())
                .thenThrow(new ScreenshotJobSubmissionUnavailableException());

        mvc.perform(validSubmission())
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("SCREENSHOT_JOB_QUEUE_FULL"));

        mvc.perform(validSubmission())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SCREENSHOT_JOB_SUBMISSION_UNAVAILABLE"));
    }

    @Test
    void queuedAndFailedJobsAreSuccessfulStatusQueries() throws Exception {
        ScreenshotJob queued = job(ScreenshotJobStatus.QUEUED);
        ScreenshotJob failed = job(ScreenshotJobStatus.FAILED);
        failed.setAttemptCount(2);
        failed.setFailureCode("SCREENSHOT_TIMEOUT");
        failed.setStartedAt(CREATED_AT.plusSeconds(1));
        failed.setCompletedAt(CREATED_AT.plusSeconds(21));
        when(service.getJob(IDENTITY, JOB_ID)).thenReturn(queued, failed);

        mvc.perform(ownedGet("/v1/web/screenshot-jobs/" + JOB_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.failureCode").doesNotExist());

        mvc.perform(ownedGet("/v1/web/screenshot-jobs/" + JOB_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.attemptCount").value(2))
                .andExpect(jsonPath("$.data.failureCode").value("SCREENSHOT_TIMEOUT"))
                .andExpect(jsonPath("$.data.completedAt").value("2026-10-23T06:30:21Z"));
    }

    @Test
    void successfulResultIsRawInlinePngWithNoStore() throws Exception {
        byte[] png = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        when(service.getResult(IDENTITY, JOB_ID)).thenReturn(png);

        mvc.perform(ownedGet("/v1/web/screenshot-jobs/" + JOB_ID + "/result"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(png))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition",
                        "inline; filename=\"screenshot-" + JOB_ID + ".png\""));
    }

    @Test
    void resultStateErrorsUse409Or410WithStableData() throws Exception {
        when(service.getResult(IDENTITY, JOB_ID))
                .thenThrow(new ScreenshotJobNotReadyException(ScreenshotJobStatus.RUNNING))
                .thenThrow(new ScreenshotJobFailedException("SCREENSHOT_TIMEOUT"))
                .thenThrow(new ScreenshotJobResultExpiredException());

        mvc.perform(ownedGet("/v1/web/screenshot-jobs/" + JOB_ID + "/result"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCREENSHOT_JOB_NOT_READY"))
                .andExpect(jsonPath("$.data.status").value("RUNNING"));

        mvc.perform(ownedGet("/v1/web/screenshot-jobs/" + JOB_ID + "/result"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCREENSHOT_JOB_FAILED"))
                .andExpect(jsonPath("$.data.failureCode").value("SCREENSHOT_TIMEOUT"));

        mvc.perform(ownedGet("/v1/web/screenshot-jobs/" + JOB_ID + "/result"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("SCREENSHOT_JOB_RESULT_EXPIRED"));
    }

    @Test
    void ownershipHidingAndMissingResultFileHaveDifferentErrors() throws Exception {
        when(service.getJob(IDENTITY, JOB_ID)).thenThrow(new ScreenshotJobNotFoundException());
        when(service.getResult(IDENTITY, JOB_ID)).thenThrow(new ScreenshotResultStoreException());

        mvc.perform(ownedGet("/v1/web/screenshot-jobs/" + JOB_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCREENSHOT_JOB_NOT_FOUND"));

        mvc.perform(ownedGet("/v1/web/screenshot-jobs/" + JOB_ID + "/result"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("SCREENSHOT_JOB_RESULT_UNAVAILABLE"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder validSubmission() {
        return post("/v1/web/screenshot-jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + URL + "\"}")
                .requestAttr(MachineIdentityResolver.ATTRIBUTE_NAME, IDENTITY);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder ownedGet(String path) {
        return get(path).requestAttr(MachineIdentityResolver.ATTRIBUTE_NAME, IDENTITY);
    }

    private static ScreenshotJob job(ScreenshotJobStatus status) {
        ScreenshotJob job = new ScreenshotJob();
        job.setJobId(JOB_ID);
        job.setApiKeyId(IDENTITY.apiKeyId());
        job.setStatus(status.code());
        job.setAttemptCount(0);
        job.setCreatedAt(CREATED_AT);
        job.setUpdatedAt(CREATED_AT);
        return job;
    }
}
