package com.nexus.webhook.dispatch;

import java.io.IOException;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/** The caller supplies the failing boundary and an immutable lease-status snapshot. */
public final class WebhookExceptionClassifier {
    public enum Operation { URL_VALIDATION, SECRET_DECRYPTION, CONFIGURATION, DELIVERY, DATABASE, LEASE, OTHER }

    public WebhookFailure classify(Throwable error, Operation operation, LeaseStatus leaseStatus) {
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(leaseStatus, "leaseStatus");
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = error; t != null && seen.add(t); t = t.getCause()) chain.add(t);
        for (Throwable t : chain) {
            if (t instanceof VirtualMachineError fatal) throw fatal;
            if (t instanceof ThreadDeath fatal) throw fatal;
        }
        if (has(chain, InterruptedException.class))
            Thread.currentThread().interrupt();
        if (leaseStatus == LeaseStatus.LEASE_LOST) return failure(FailureCategory.LEASE, "LEASE_LOST");
        if (leaseStatus == LeaseStatus.LEASE_UNCERTAIN) return failure(FailureCategory.LEASE, "LEASE_UNCERTAIN");
        if (has(chain, LeaseLostException.class)) return failure(FailureCategory.LEASE, "LEASE_LOST");
        if (has(chain, LeaseUncertainException.class)) return failure(FailureCategory.LEASE, "LEASE_UNCERTAIN");
        if (operation == Operation.DATABASE || has(chain, SQLException.class)
                || has(chain, DataAccessException.class) || has(chain, TransactionException.class))
            return failure(FailureCategory.INFRASTRUCTURE, "DATABASE_UNCERTAIN");
        if (has(chain, CancellationException.class)) {
            if (leaseStatus == LeaseStatus.COMPLETED) return failure(FailureCategory.INFRASTRUCTURE, "LEASE_COMPLETED");
            return failure(FailureCategory.INFRASTRUCTURE, "CANCELLED");
        }
        if (has(chain, InterruptedException.class))
            return failure(FailureCategory.INFRASTRUCTURE, "INTERRUPTED");
        if (has(chain, WebhookDisallowedAddressException.class)) return failure(FailureCategory.PERMANENT, "ADDRESS_DISALLOWED");
        if (operation == Operation.URL_VALIDATION && has(chain, UnknownHostException.class))
            return failure(FailureCategory.RETRYABLE, "DNS_RESOLUTION_FAILED");
        int index = 0;
        while (index + 1 < chain.size() && (chain.get(index) instanceof CompletionException || chain.get(index) instanceof ExecutionException)) index++;
        Throwable contextual = chain.get(index);
        if (operation == Operation.URL_VALIDATION && contextual instanceof IllegalArgumentException)
            return failure(FailureCategory.PERMANENT, "INVALID_URL");
        if (operation == Operation.SECRET_DECRYPTION && (contextual instanceof IllegalArgumentException
                || contextual instanceof GeneralSecurityException
                || contextual instanceof IllegalStateException && has(chain, GeneralSecurityException.class)))
            return failure(FailureCategory.PERMANENT, "SECRET_DECRYPTION_FAILED");
        if (operation == Operation.CONFIGURATION && contextual instanceof IllegalArgumentException)
            return failure(FailureCategory.PERMANENT, "CONFIGURATION_ERROR");
        if (has(chain, CertificateException.class) || has(chain, CertPathBuilderException.class)
                || has(chain, CertPathValidatorException.class) || has(chain, SSLHandshakeException.class)
                || has(chain, SSLPeerUnverifiedException.class))
            return failure(FailureCategory.PERMANENT, "TLS_FAILED");
        if (operation == Operation.DELIVERY && has(chain, IOException.class)) return failure(FailureCategory.RETRYABLE, "NETWORK_ERROR");
        return failure(FailureCategory.INFRASTRUCTURE, "UNCLASSIFIED_ERROR");
    }

    private static boolean has(List<Throwable> chain, Class<? extends Throwable> type) {
        return chain.stream().anyMatch(type::isInstance);
    }

    private static WebhookFailure failure(FailureCategory category, String code) {
        return new WebhookFailure(category, code);
    }
}
