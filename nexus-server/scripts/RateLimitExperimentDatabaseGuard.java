import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Test-only database identity guard and exact cleanup helper for the controlled
 * Application rate-limit experiments. Credentials are read only from the
 * process environment and are never printed.
 */
public final class RateLimitExperimentDatabaseGuard {
    private static final String EXPECTED_DATABASE = "nexus_test";
    private static final String EXPECTED_USER_PREFIX = "nexus_test_user@";

    private RateLimitExperimentDatabaseGuard() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("Expected preflight or cleanup");
        }
        try (Connection connection = connect()) {
            requireDedicatedDatabase(connection);
            switch (args[0]) {
                case "preflight" -> System.out.println(
                        "DB_PREFLIGHT_OK database=nexus_test user=nexus_test_user");
                case "cleanup" -> cleanup(connection, args);
                case "verify-clean" -> verifyClean(connection, args);
                case "cleanup-pair" -> cleanupPair(connection, args);
                case "verify-clean-pair" -> verifyCleanPair(connection, args);
                case "cleanup-batch" -> cleanupBatch(connection, args);
                case "verify-clean-batch" -> verifyCleanBatch(connection, args);
                case "inspect-batch" -> inspectBatch(connection, args);
                default -> throw new IllegalArgumentException("Unknown action: " + args[0]);
            }
        }
    }

    private static Connection connect() throws SQLException {
        String url = requireEnvironment("NEXUS_EXPERIMENT_DB_URL");
        String username = requireEnvironment("NEXUS_EXPERIMENT_DB_USERNAME");
        String password = requireEnvironment("NEXUS_EXPERIMENT_DB_PASSWORD");
        return DriverManager.getConnection(url, username, password);
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private static void requireDedicatedDatabase(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT DATABASE(), CURRENT_USER()")) {
            if (!result.next()) {
                throw new IllegalStateException("Database identity query returned no row");
            }
            String database = result.getString(1);
            String user = result.getString(2);
            if (!EXPECTED_DATABASE.equals(database)
                    || user == null
                    || !user.startsWith(EXPECTED_USER_PREFIX)) {
                throw new IllegalStateException(
                        "Refusing experiment database: expected nexus_test and nexus_test_user");
            }
        }
    }

    private static void cleanup(Connection connection, String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "cleanup requires applicationId, apiKeyId and expectedEmail");
        }
        long applicationId = Long.parseLong(args[1]);
        long apiKeyId = Long.parseLong(args[2]);
        String expectedEmail = args[3];
        if (applicationId <= 0 || apiKeyId <= 0 || expectedEmail.isBlank()) {
            throw new IllegalArgumentException("Cleanup identifiers must be positive and email nonblank");
        }

        connection.setAutoCommit(false);
        try {
            long ownerId = lockAndVerifyOwner(connection, applicationId, expectedEmail);
            verifyKey(connection, applicationId, apiKeyId);

            int screenshotJobs = delete(connection,
                    "DELETE FROM screenshot_jobs WHERE api_key_id IN "
                            + "(SELECT id FROM api_keys WHERE application_id = ?)", applicationId);
            int shortLinks = delete(connection,
                    "DELETE FROM short_links WHERE api_key_id IN "
                            + "(SELECT id FROM api_keys WHERE application_id = ?)", applicationId);
            int usageEvents = delete(connection,
                    "DELETE FROM usage_events WHERE application_id = ?", applicationId);
            int quotaPeriods = delete(connection,
                    "DELETE FROM application_quotas WHERE application_id = ?", applicationId);
            int apiKeys = delete(connection,
                    "DELETE FROM api_keys WHERE application_id = ?", applicationId);
            int applications = delete(connection,
                    "DELETE FROM applications WHERE id = ?", applicationId);
            int users = delete(connection,
                    "DELETE FROM users WHERE id = ?", ownerId);

            if (applications != 1 || users != 1 || apiKeys < 1) {
                throw new IllegalStateException("Cleanup affected an unexpected number of parent rows");
            }
            connection.commit();
            System.out.printf(
                    "DB_CLEANUP_OK screenshotJobs=%d shortLinks=%d usageEvents=%d quotaPeriods=%d apiKeys=%d applications=%d users=%d%n",
                    screenshotJobs, shortLinks, usageEvents, quotaPeriods, apiKeys, applications, users);
        } catch (Exception exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static long lockAndVerifyOwner(
            Connection connection, long applicationId, String expectedEmail) throws SQLException {
        String sql = """
                SELECT a.owner_user_id, u.email
                FROM applications a
                JOIN users u ON u.id = a.owner_user_id
                WHERE a.id = ?
                FOR UPDATE
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, applicationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !expectedEmail.equals(result.getString(2))) {
                    throw new IllegalStateException("Application owner does not match this experiment run");
                }
                return result.getLong(1);
            }
        }
    }

    private static void verifyKey(
            Connection connection, long applicationId, long apiKeyId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM api_keys WHERE id = ? AND application_id = ?")) {
            statement.setLong(1, apiKeyId);
            statement.setLong(2, applicationId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                if (result.getInt(1) != 1) {
                    throw new IllegalStateException("API Key does not match this experiment run");
                }
            }
        }
    }

    private static void verifyClean(Connection connection, String[] args) throws SQLException {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "verify-clean requires applicationId, apiKeyId and expectedEmail");
        }
        long applicationId = Long.parseLong(args[1]);
        long apiKeyId = Long.parseLong(args[2]);
        String expectedEmail = args[3];
        requireZero(connection, "SELECT COUNT(*) FROM screenshot_jobs WHERE api_key_id = ?", apiKeyId);
        requireZero(connection, "SELECT COUNT(*) FROM short_links WHERE api_key_id = ?", apiKeyId);
        requireZero(connection, "SELECT COUNT(*) FROM usage_events WHERE application_id = ?", applicationId);
        requireZero(connection, "SELECT COUNT(*) FROM application_quotas WHERE application_id = ?", applicationId);
        requireZero(connection, "SELECT COUNT(*) FROM api_keys WHERE application_id = ?", applicationId);
        requireZero(connection, "SELECT COUNT(*) FROM applications WHERE id = ?", applicationId);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM users WHERE email = ?")) {
            statement.setString(1, expectedEmail);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                if (result.getInt(1) != 0) {
                    throw new IllegalStateException("Experiment user still exists after cleanup");
                }
            }
        }
        System.out.println("DB_CLEANUP_VERIFIED all experiment rows absent");
    }

    private static void cleanupPair(Connection connection, String[] args) throws Exception {
        PairArguments pair = PairArguments.parse(args, "cleanup-pair");
        connection.setAutoCommit(false);
        try {
            long firstOwner = lockAndVerifyOwner(
                    connection, pair.firstApplicationId(), pair.expectedEmail());
            long secondOwner = lockAndVerifyOwner(
                    connection, pair.secondApplicationId(), pair.expectedEmail());
            if (firstOwner != secondOwner) {
                throw new IllegalStateException("Experiment Applications do not share the expected owner");
            }
            verifyKey(connection, pair.firstApplicationId(), pair.firstApiKeyId());
            verifyKey(connection, pair.secondApplicationId(), pair.secondApiKeyId());

            int screenshotJobs = deleteChildren(connection, "screenshot_jobs", pair);
            int shortLinks = deleteChildren(connection, "short_links", pair);
            int usageEvents = deleteForApplications(connection, "usage_events", pair);
            int quotaPeriods = deleteForApplications(connection, "application_quotas", pair);
            int apiKeys = deleteForApplications(connection, "api_keys", pair);
            int applications = deleteApplications(connection, pair);
            int users = delete(connection, "DELETE FROM users WHERE id = ?", firstOwner);

            if (applications != 2 || users != 1 || apiKeys < 2) {
                throw new IllegalStateException("Pair cleanup affected an unexpected number of parent rows");
            }
            connection.commit();
            System.out.printf(
                    "DB_PAIR_CLEANUP_OK screenshotJobs=%d shortLinks=%d usageEvents=%d quotaPeriods=%d apiKeys=%d applications=%d users=%d%n",
                    screenshotJobs, shortLinks, usageEvents, quotaPeriods, apiKeys, applications, users);
        } catch (Exception exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void verifyCleanPair(Connection connection, String[] args) throws SQLException {
        PairArguments pair = PairArguments.parse(args, "verify-clean-pair");
        requireZero(connection, "SELECT COUNT(*) FROM screenshot_jobs WHERE api_key_id = ?",
                pair.firstApiKeyId());
        requireZero(connection, "SELECT COUNT(*) FROM screenshot_jobs WHERE api_key_id = ?",
                pair.secondApiKeyId());
        requireZero(connection, "SELECT COUNT(*) FROM short_links WHERE api_key_id = ?",
                pair.firstApiKeyId());
        requireZero(connection, "SELECT COUNT(*) FROM short_links WHERE api_key_id = ?",
                pair.secondApiKeyId());
        requireApplicationAbsent(connection, pair.firstApplicationId());
        requireApplicationAbsent(connection, pair.secondApplicationId());
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM users WHERE email = ?")) {
            statement.setString(1, pair.expectedEmail());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                if (result.getInt(1) != 0) {
                    throw new IllegalStateException("Experiment user still exists after pair cleanup");
                }
            }
        }
        System.out.println("DB_PAIR_CLEANUP_VERIFIED all experiment rows absent");
    }

    private static void cleanupBatch(Connection connection, String[] args) throws Exception {
        BatchArguments batch = BatchArguments.parse(args, "cleanup-batch");
        connection.setAutoCommit(false);
        try {
            Long ownerId = null;
            for (BatchResource resource : batch.resources()) {
                long resourceOwner = lockAndVerifyOwner(
                        connection, resource.applicationId(), batch.expectedEmail());
                if (ownerId != null && ownerId != resourceOwner) {
                    throw new IllegalStateException(
                            "Experiment Applications do not share the expected owner");
                }
                ownerId = resourceOwner;
                verifyKey(connection, resource.applicationId(), resource.apiKeyId());
            }
            if (ownerId == null) {
                throw new IllegalStateException("Batch cleanup requires experiment resources");
            }
            requireExactOwnerApplicationCount(
                    connection, ownerId, batch.resources().size());

            int screenshotJobs = deleteBatchChildren(connection, "screenshot_jobs", batch);
            int shortLinks = deleteBatchChildren(connection, "short_links", batch);
            int usageEvents = deleteBatchApplications(connection, "usage_events", batch);
            int quotaPeriods = deleteBatchApplications(connection, "application_quotas", batch);
            int apiKeys = deleteBatchApplications(connection, "api_keys", batch);
            int applications = deleteBatchApplications(connection, "applications", batch);
            int users = delete(connection, "DELETE FROM users WHERE id = ?", ownerId);

            int expectedApplications = batch.resources().size();
            if (applications != expectedApplications
                    || users != 1
                    || apiKeys < expectedApplications) {
                throw new IllegalStateException(
                        "Batch cleanup affected an unexpected number of parent rows");
            }
            connection.commit();
            System.out.printf(
                    "DB_BATCH_CLEANUP_OK screenshotJobs=%d shortLinks=%d usageEvents=%d quotaPeriods=%d apiKeys=%d applications=%d users=%d%n",
                    screenshotJobs, shortLinks, usageEvents, quotaPeriods,
                    apiKeys, applications, users);
        } catch (Exception exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void verifyCleanBatch(Connection connection, String[] args) throws SQLException {
        BatchArguments batch = BatchArguments.parse(args, "verify-clean-batch");
        for (BatchResource resource : batch.resources()) {
            requireZero(connection, "SELECT COUNT(*) FROM screenshot_jobs WHERE api_key_id = ?",
                    resource.apiKeyId());
            requireZero(connection, "SELECT COUNT(*) FROM short_links WHERE api_key_id = ?",
                    resource.apiKeyId());
            requireApplicationAbsent(connection, resource.applicationId());
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM users WHERE email = ?")) {
            statement.setString(1, batch.expectedEmail());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                if (result.getInt(1) != 0) {
                    throw new IllegalStateException(
                            "Experiment user still exists after batch cleanup");
                }
            }
        }
        System.out.println("DB_BATCH_CLEANUP_VERIFIED all experiment rows absent");
    }

    private static void inspectBatch(Connection connection, String[] args) throws SQLException {
        BatchArguments batch = BatchArguments.parse(args, "inspect-batch");
        for (BatchResource resource : batch.resources()) {
            lockAndVerifyOwner(connection, resource.applicationId(), batch.expectedEmail());
            verifyKey(connection, resource.applicationId(), resource.apiKeyId());
            long usageTotal;
            long usage200;
            long usage500;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT COUNT(*),
                           COALESCE(SUM(http_status_code = 200), 0),
                           COALESCE(SUM(http_status_code = 500), 0)
                    FROM usage_events
                    WHERE application_id = ? AND api_key_id = ?
                    """)) {
                statement.setLong(1, resource.applicationId());
                statement.setLong(2, resource.apiKeyId());
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    usageTotal = result.getLong(1);
                    usage200 = result.getLong(2);
                    usage500 = result.getLong(3);
                }
            }
            long quotaUsed;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COALESCE(SUM(used_count), 0) FROM application_quotas WHERE application_id = ?")) {
                statement.setLong(1, resource.applicationId());
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    quotaUsed = result.getLong(1);
                }
            }
            System.out.printf(
                    "DB_BATCH_RESOURCE applicationId=%d usageTotal=%d usage200=%d usage500=%d quotaUsed=%d%n",
                    resource.applicationId(), usageTotal, usage200, usage500, quotaUsed);
        }
    }

    private static void requireExactOwnerApplicationCount(
            Connection connection, long ownerId, int expectedCount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM applications WHERE owner_user_id = ?")) {
            statement.setLong(1, ownerId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                if (result.getInt(1) != expectedCount) {
                    throw new IllegalStateException(
                            "Experiment owner has Applications outside the cleanup batch");
                }
            }
        }
    }

    private static int deleteBatchChildren(
            Connection connection, String table, BatchArguments batch) throws SQLException {
        String sql = "DELETE FROM " + table + " WHERE api_key_id IN "
                + "(SELECT id FROM api_keys WHERE application_id IN ("
                + placeholders(batch.resources().size()) + "))";
        return executeBatchDelete(connection, sql, batch);
    }

    private static int deleteBatchApplications(
            Connection connection, String table, BatchArguments batch) throws SQLException {
        String idColumn = "applications".equals(table) ? "id" : "application_id";
        String sql = "DELETE FROM " + table + " WHERE " + idColumn + " IN ("
                + placeholders(batch.resources().size()) + ")";
        return executeBatchDelete(connection, sql, batch);
    }

    private static int executeBatchDelete(
            Connection connection, String sql, BatchArguments batch) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            for (BatchResource resource : batch.resources()) {
                statement.setLong(parameter++, resource.applicationId());
            }
            return statement.executeUpdate();
        }
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private static void requireApplicationAbsent(Connection connection, long applicationId)
            throws SQLException {
        requireZero(connection, "SELECT COUNT(*) FROM usage_events WHERE application_id = ?", applicationId);
        requireZero(connection, "SELECT COUNT(*) FROM application_quotas WHERE application_id = ?", applicationId);
        requireZero(connection, "SELECT COUNT(*) FROM api_keys WHERE application_id = ?", applicationId);
        requireZero(connection, "SELECT COUNT(*) FROM applications WHERE id = ?", applicationId);
    }

    private static int deleteChildren(Connection connection, String table, PairArguments pair)
            throws SQLException {
        String sql = "DELETE FROM " + table + " WHERE api_key_id IN "
                + "(SELECT id FROM api_keys WHERE application_id IN (?, ?))";
        return deletePair(connection, sql, pair.firstApplicationId(), pair.secondApplicationId());
    }

    private static int deleteForApplications(Connection connection, String table, PairArguments pair)
            throws SQLException {
        return deletePair(connection,
                "DELETE FROM " + table + " WHERE application_id IN (?, ?)",
                pair.firstApplicationId(), pair.secondApplicationId());
    }

    private static int deleteApplications(Connection connection, PairArguments pair)
            throws SQLException {
        return deletePair(connection, "DELETE FROM applications WHERE id IN (?, ?)",
                pair.firstApplicationId(), pair.secondApplicationId());
    }

    private static int deletePair(Connection connection, String sql, long first, long second)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, first);
            statement.setLong(2, second);
            return statement.executeUpdate();
        }
    }

    private static void requireZero(Connection connection, String sql, long identifier)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, identifier);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                if (result.getInt(1) != 0) {
                    throw new IllegalStateException("Experiment row still exists after cleanup");
                }
            }
        }
    }

    private static int delete(Connection connection, String sql, long identifier)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, identifier);
            return statement.executeUpdate();
        }
    }

    private record PairArguments(
            long firstApplicationId,
            long firstApiKeyId,
            long secondApplicationId,
            long secondApiKeyId,
            String expectedEmail) {

        private static PairArguments parse(String[] args, String action) {
            if (args.length != 6) {
                throw new IllegalArgumentException(action
                        + " requires firstApplicationId, firstApiKeyId, secondApplicationId, secondApiKeyId and expectedEmail");
            }
            PairArguments pair = new PairArguments(
                    Long.parseLong(args[1]), Long.parseLong(args[2]),
                    Long.parseLong(args[3]), Long.parseLong(args[4]), args[5]);
            if (pair.firstApplicationId <= 0 || pair.firstApiKeyId <= 0
                    || pair.secondApplicationId <= 0 || pair.secondApiKeyId <= 0
                    || pair.firstApplicationId == pair.secondApplicationId
                    || pair.expectedEmail.isBlank()) {
                throw new IllegalArgumentException("Pair cleanup identifiers are invalid");
            }
            return pair;
        }
    }

    private record BatchResource(long applicationId, long apiKeyId) {
    }

    private record BatchArguments(String expectedEmail, List<BatchResource> resources) {

        private static BatchArguments parse(String[] args, String action) {
            if (args.length < 4) {
                throw new IllegalArgumentException(
                        action + " requires expectedEmail and at least two applicationId:apiKeyId pairs");
            }
            String expectedEmail = args[1];
            if (expectedEmail.isBlank()) {
                throw new IllegalArgumentException("Batch cleanup email must be nonblank");
            }
            List<BatchResource> resources = new ArrayList<>();
            Set<Long> applicationIds = new HashSet<>();
            Set<Long> apiKeyIds = new HashSet<>();
            for (int index = 2; index < args.length; index++) {
                String[] parts = args[index].split(":", -1);
                if (parts.length != 2) {
                    throw new IllegalArgumentException(
                            "Batch resource must use applicationId:apiKeyId");
                }
                long applicationId = Long.parseLong(parts[0]);
                long apiKeyId = Long.parseLong(parts[1]);
                if (applicationId <= 0 || apiKeyId <= 0
                        || !applicationIds.add(applicationId)
                        || !apiKeyIds.add(apiKeyId)) {
                    throw new IllegalArgumentException(
                            "Batch cleanup identifiers must be positive and unique");
                }
                resources.add(new BatchResource(applicationId, apiKeyId));
            }
            if (resources.size() < 2) {
                throw new IllegalArgumentException(
                        "Batch cleanup requires at least two resources");
            }
            return new BatchArguments(expectedEmail, List.copyOf(resources));
        }
    }
}
