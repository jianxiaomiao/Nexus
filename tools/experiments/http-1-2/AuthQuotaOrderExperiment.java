import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/** Experiment 16 / HTTP-1 and HTTP-2: authentication -> rate limit -> Quota -> business. */
public class AuthQuotaOrderExperiment {
    public static void main(String[] args) {
        FakeAuthenticator authenticator = new FakeAuthenticator(Map.of(
                "key-a", new ApiKeyIdentity(101, 9001)
        ));
        ScriptedRateLimiter limiter = new ScriptedRateLimiter(List.of(
                RateDecision.REJECTED_QUEUE_FULL,
                RateDecision.ALLOWED,
                RateDecision.ALLOWED
        ));
        FakeQuota quota = new FakeQuota(1);
        FakeBusiness business = new FakeBusiness();
        MachinePipeline pipeline = new MachinePipeline(authenticator, limiter, quota, business);

        Response invalid = pipeline.handle("missing-key");
        assertResponse(401, "INVALID_API_KEY_CREDENTIAL", invalid, "invalid credential");
        assertEquals(List.of("authenticate"), invalid.trace(), "invalid trace");
        assertEquals(0, limiter.calls, "invalid credential limiter calls");
        assertEquals(0, quota.calls, "invalid credential Quota calls");

        Response rateRejected = pipeline.handle("key-a");
        assertResponse(429, "APPLICATION_RATE_LIMIT_QUEUE_FULL", rateRejected,
                "rate rejection");
        assertEquals(List.of("authenticate", "rateLimit"), rateRejected.trace(),
                "rate rejection trace");
        assertEquals(0, quota.calls, "rate rejection Quota calls");
        assertEquals(0, business.calls, "rate rejection business calls");

        Response success = pipeline.handle("key-a");
        assertResponse(200, "OK", success, "successful request");
        assertEquals(List.of("authenticate", "rateLimit", "quota", "business"),
                success.trace(), "success trace");
        assertEquals(1, quota.used, "successful Quota usage");
        assertEquals(1, business.calls, "successful business calls");

        Response quotaRejected = pipeline.handle("key-a");
        assertResponse(429, "APPLICATION_QUOTA_EXHAUSTED", quotaRejected,
                "Quota rejection");
        assertEquals(List.of("authenticate", "rateLimit", "quota"), quotaRejected.trace(),
                "Quota rejection trace");
        assertEquals(2, quota.calls, "Quota calls after exhausted request");
        assertEquals(1, quota.used, "Quota usage remains capped");
        assertEquals(1, business.calls, "Quota rejection business calls");
        assertEquals(3, limiter.calls, "only authenticated requests reached limiter");

        System.out.println("invalid -> 401; limiterCalls=0 quotaCalls=0");
        System.out.println("rate rejected -> 429; quotaCalls=0 businessCalls=0");
        System.out.println("allowed -> rate permit, Quota used once, business called once");
        System.out.println("Quota exhausted -> rate permit consumed, Quota 429, business not called");
        System.out.println("HTTP-1/2 PASS: authentication, rate limit, Quota and business order is correct.");
    }

    private static void assertResponse(int status, String code, Response actual, String description) {
        assertEquals(status, actual.status(), description + " status");
        assertEquals(code, actual.code(), description + " code");
    }

    private static void assertEquals(long expected, long actual, String description) {
        if (expected != actual) {
            throw new AssertionError(description + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!expected.equals(actual)) {
            throw new AssertionError(description + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private record ApiKeyIdentity(long apiKeyId, long applicationId) {
    }

    private record Response(int status, String code, List<String> trace) {
    }

    private enum RateDecision {
        ALLOWED,
        REJECTED_QUEUE_FULL
    }

    private static final class MachinePipeline {
        private final FakeAuthenticator authenticator;
        private final ScriptedRateLimiter limiter;
        private final FakeQuota quota;
        private final FakeBusiness business;

        private MachinePipeline(
                FakeAuthenticator authenticator,
                ScriptedRateLimiter limiter,
                FakeQuota quota,
                FakeBusiness business) {
            this.authenticator = authenticator;
            this.limiter = limiter;
            this.quota = quota;
            this.business = business;
        }

        Response handle(String credential) {
            List<String> trace = new ArrayList<>();
            trace.add("authenticate");
            ApiKeyIdentity identity = authenticator.authenticate(credential);
            if (identity == null) {
                return new Response(401, "INVALID_API_KEY_CREDENTIAL", List.copyOf(trace));
            }

            trace.add("rateLimit");
            if (limiter.acquire(identity.applicationId()) != RateDecision.ALLOWED) {
                return new Response(429, "APPLICATION_RATE_LIMIT_QUEUE_FULL", List.copyOf(trace));
            }

            trace.add("quota");
            if (!quota.consume(identity.applicationId())) {
                return new Response(429, "APPLICATION_QUOTA_EXHAUSTED", List.copyOf(trace));
            }

            trace.add("business");
            business.execute(identity);
            return new Response(200, "OK", List.copyOf(trace));
        }
    }

    private static final class FakeAuthenticator {
        private final Map<String, ApiKeyIdentity> identities;

        private FakeAuthenticator(Map<String, ApiKeyIdentity> identities) {
            this.identities = identities;
        }

        ApiKeyIdentity authenticate(String credential) {
            return identities.get(credential);
        }
    }

    private static final class ScriptedRateLimiter {
        private final Queue<RateDecision> decisions;
        private int calls;

        private ScriptedRateLimiter(List<RateDecision> decisions) {
            this.decisions = new ArrayDeque<>(decisions);
        }

        RateDecision acquire(long applicationId) {
            calls++;
            RateDecision decision = decisions.poll();
            if (decision == null) {
                throw new AssertionError("missing scripted rate decision for Application " + applicationId);
            }
            return decision;
        }
    }

    private static final class FakeQuota {
        private final int limit;
        private int calls;
        private int used;

        private FakeQuota(int limit) {
            this.limit = limit;
        }

        boolean consume(long applicationId) {
            calls++;
            if (used >= limit) {
                return false;
            }
            used++;
            return true;
        }
    }

    private static final class FakeBusiness {
        private int calls;

        void execute(ApiKeyIdentity identity) {
            calls++;
        }
    }
}
