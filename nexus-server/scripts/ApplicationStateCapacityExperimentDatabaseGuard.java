import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Database identity, accounting and exact-cleanup guard for the controlled
 * Application rate-limit state capacity experiment. Secrets are read only
 * from process environment variables and are never printed.
 */
public final class ApplicationStateCapacityExperimentDatabaseGuard {
    private static final String EXPECTED_DATABASE = "nexus_test";
    private static final String EXPECTED_USER_PREFIX = "nexus_test_user@";
    private static final String EMAIL_PREFIX = "obs-state-";
    private static final String APPLICATION_PREFIX = "obs-state-app-";
    private static final String KEY_PREFIX = "obs-state-key-";
    private static final int EXPECTED_APPLICATIONS = 1_001;

    private ApplicationStateCapacityExperimentDatabaseGuard() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("Expected an action");
        }
        try (Connection connection = connect()) {
            requireDedicatedDatabase(connection);
            switch (args[0]) {
                case "preflight" -> System.out.println(
                        "DB_PREFLIGHT_OK database=nexus_test user=nexus_test_user");
                case "wait-accounting" -> waitForAccounting(
                        connection, requireEmail(args), requireRounds(args));
                case "inspect" -> inspect(
                        connection, requireEmail(args), requireRounds(args), true);
                case "cleanup" -> cleanup(connection, requireEmail(args));
                case "verify-clean" -> verifyClean(connection, requireEmail(args));
                default -> throw new IllegalArgumentException("Unknown action: " + args[0]);
            }
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                requireEnvironment("NEXUS_EXPERIMENT_DB_URL"),
                requireEnvironment("NEXUS_EXPERIMENT_DB_USERNAME"),
                requireEnvironment("NEXUS_EXPERIMENT_DB_PASSWORD"));
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
            if (!result.next()
                    || !EXPECTED_DATABASE.equals(result.getString(1))
                    || result.getString(2) == null
                    || !result.getString(2).startsWith(EXPECTED_USER_PREFIX)) {
                throw new IllegalStateException(
                        "Refusing experiment database: expected nexus_test and nexus_test_user");
            }
        }
    }

    private static String requireEmail(String[] args) {
        if (args.length < 2 || !args[1].startsWith(EMAIL_PREFIX)
                || !args[1].endsWith("@example.test")) {
            throw new IllegalArgumentException("Expected exact experiment email");
        }
        return args[1];
    }

    private static int requireRounds(String[] args) {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected email and positive round count");
        }
        int rounds = Integer.parseInt(args[2]);
        if (rounds <= 0 || rounds > 10) {
            throw new IllegalArgumentException("Round count is outside the experiment boundary");
        }
        return rounds;
    }

    private static void waitForAccounting(
            Connection connection, String email, int rounds) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        Accounting last = null;
        while (System.nanoTime() < deadline) {
            last = inspect(connection, email, rounds, false);
            if (last.complete()) {
                System.out.printf(
                        "DB_ACCOUNTING_CONVERGED applications=%d keys=%d usage=%d quota=%d%n",
                        last.applications(), last.keys(), last.usageTotal(), last.quotaTotal());
                return;
            }
            Thread.sleep(200L);
        }
        throw new IllegalStateException("Accounting did not converge: " + last);
    }

    private static Accounting inspect(
            Connection connection, String email, int rounds, boolean requireComplete)
            throws SQLException {
        Long userId = selectUser(connection, email, false);
        if (userId == null) {
            throw new IllegalStateException("Experiment user is absent");
        }
        List<Long> applicationIds = selectAndVerifyApplications(
                connection, userId, false, true);
        List<Long> keyIds = selectAndVerifyKeys(
                connection, applicationIds, false, true);
        int expectedSuccesses = EXPECTED_APPLICATIONS * rounds;

        long usageTotal = scalar(connection, """
                SELECT COUNT(*)
                FROM usage_events
                WHERE application_id IN (%s)
                """.formatted(placeholders(applicationIds.size())), applicationIds);
        long usageUnexpected = scalar(connection, """
                SELECT COUNT(*)
                FROM usage_events
                WHERE application_id IN (%s)
                  AND (http_status_code <> 200 OR api_code <> 'uuid.generate')
                """.formatted(placeholders(applicationIds.size())), applicationIds);
        long usagePerApplicationMismatch = scalar(connection, """
                SELECT COUNT(*)
                FROM (
                    SELECT a.id, COUNT(u.id) AS actual
                    FROM applications a
                    LEFT JOIN usage_events u ON u.application_id = a.id
                    WHERE a.owner_user_id = ?
                    GROUP BY a.id
                    HAVING actual <> ?
                ) mismatches
                """, List.of(userId, (long) rounds));
        long quotaTotal = scalar(connection, """
                SELECT COALESCE(SUM(q.used_count), 0)
                FROM application_quotas q
                WHERE q.application_id IN (%s)
                """.formatted(placeholders(applicationIds.size())), applicationIds);
        long quotaPerApplicationMismatch = scalar(connection, """
                SELECT COUNT(*)
                FROM (
                    SELECT a.id, COALESCE(SUM(q.used_count), 0) AS actual
                    FROM applications a
                    LEFT JOIN application_quotas q ON q.application_id = a.id
                    WHERE a.owner_user_id = ?
                    GROUP BY a.id
                    HAVING actual <> ?
                ) mismatches
                """, List.of(userId, (long) rounds));
        long screenshotJobs = scalar(connection,
                "SELECT COUNT(*) FROM screenshot_jobs WHERE api_key_id IN (%s)"
                        .formatted(placeholders(keyIds.size())), keyIds);
        long shortLinks = scalar(connection,
                "SELECT COUNT(*) FROM short_links WHERE api_key_id IN (%s)"
                        .formatted(placeholders(keyIds.size())), keyIds);

        Accounting accounting = new Accounting(
                applicationIds.size(), keyIds.size(), usageTotal, usageUnexpected,
                usagePerApplicationMismatch, quotaTotal, quotaPerApplicationMismatch,
                screenshotJobs, shortLinks, expectedSuccesses);
        if (requireComplete && !accounting.complete()) {
            throw new IllegalStateException("Accounting mismatch: " + accounting);
        }
        if (requireComplete) {
            System.out.printf(
                    "DB_INSPECT_OK applications=%d keys=%d usage=%d quota=%d "
                            + "screenshotJobs=%d shortLinks=%d%n",
                    accounting.applications(), accounting.keys(), accounting.usageTotal(),
                    accounting.quotaTotal(), accounting.screenshotJobs(), accounting.shortLinks());
        }
        return accounting;
    }

    private static void cleanup(Connection connection, String email) throws SQLException {
        connection.setAutoCommit(false);
        try {
            Long userId = selectUser(connection, email, true);
            if (userId == null) {
                connection.commit();
                System.out.println("DB_CLEANUP_OK alreadyAbsent=true");
                return;
            }
            List<Long> applicationIds = selectAndVerifyApplications(
                    connection, userId, true, false);
            List<Long> keyIds = selectAndVerifyKeys(
                    connection, applicationIds, true, false);

            int screenshotJobs = deleteByIds(connection,
                    "DELETE FROM screenshot_jobs WHERE api_key_id IN (%s)", keyIds);
            int shortLinks = deleteByIds(connection,
                    "DELETE FROM short_links WHERE api_key_id IN (%s)", keyIds);
            int usageEvents = deleteByIds(connection,
                    "DELETE FROM usage_events WHERE application_id IN (%s)", applicationIds);
            int quotaPeriods = deleteByIds(connection,
                    "DELETE FROM application_quotas WHERE application_id IN (%s)", applicationIds);
            int apiKeys = deleteByIds(connection,
                    "DELETE FROM api_keys WHERE application_id IN (%s)", applicationIds);
            int applications = deleteByIds(connection,
                    "DELETE FROM applications WHERE id IN (%s)", applicationIds);
            int users = deleteOne(connection, "DELETE FROM users WHERE id = ?", userId);

            if (users != 1 || applications != applicationIds.size()
                    || apiKeys != keyIds.size()) {
                throw new IllegalStateException("Cleanup affected unexpected parent row counts");
            }
            connection.commit();
            System.out.printf(
                    "DB_CLEANUP_OK screenshotJobs=%d shortLinks=%d usageEvents=%d "
                            + "quotaPeriods=%d apiKeys=%d applications=%d users=%d%n",
                    screenshotJobs, shortLinks, usageEvents, quotaPeriods,
                    apiKeys, applications, users);
        } catch (Exception exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static Long selectUser(Connection connection, String email, boolean lock)
            throws SQLException {
        String sql = "SELECT id FROM users WHERE email = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, email);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                long id = result.getLong(1);
                if (result.next()) {
                    throw new IllegalStateException("Experiment email matched multiple users");
                }
                return id;
            }
        }
    }

    private static List<Long> selectAndVerifyApplications(
            Connection connection, long userId, boolean lock, boolean requireExpectedCount)
            throws SQLException {
        List<Long> ids = new ArrayList<>();
        String sql = "SELECT id, name FROM applications WHERE owner_user_id = ?"
                + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String name = result.getString(2);
                    if (name == null || !name.startsWith(APPLICATION_PREFIX)) {
                        throw new IllegalStateException(
                                "Refusing access to a non-experiment Application");
                    }
                    ids.add(result.getLong(1));
                }
            }
        }
        if (requireExpectedCount && ids.size() != EXPECTED_APPLICATIONS) {
            throw new IllegalStateException(
                    "Expected exactly " + EXPECTED_APPLICATIONS + " experiment Applications, got "
                            + ids.size());
        }
        return ids;
    }

    private static List<Long> selectAndVerifyKeys(
            Connection connection, List<Long> applicationIds, boolean lock,
            boolean requireExpectedCount) throws SQLException {
        if (applicationIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        String sql = "SELECT id, name FROM api_keys WHERE application_id IN ("
                + placeholders(applicationIds.size()) + ")" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindValues(statement, applicationIds);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String name = result.getString(2);
                    if (name == null || !name.startsWith(KEY_PREFIX)) {
                        throw new IllegalStateException(
                                "Refusing access to a non-experiment API Key");
                    }
                    ids.add(result.getLong(1));
                }
            }
        }
        if (requireExpectedCount && ids.size() != EXPECTED_APPLICATIONS) {
            throw new IllegalStateException(
                    "Expected exactly " + EXPECTED_APPLICATIONS + " experiment API Keys, got "
                            + ids.size());
        }
        return ids;
    }

    private static long scalar(Connection connection, String sql, List<Long> values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindValues(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static void bindValues(PreparedStatement statement, List<Long> values)
            throws SQLException {
        for (int index = 0; index < values.size(); index++) {
            statement.setLong(index + 1, values.get(index));
        }
    }

    private static int deleteByIds(
            Connection connection, String template, List<Long> ids) throws SQLException {
        if (ids.isEmpty()) {
            return 0;
        }
        String sql = template.formatted(placeholders(ids.size()));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindValues(statement, ids);
            return statement.executeUpdate();
        }
    }

    private static int deleteOne(Connection connection, String sql, long id)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            return statement.executeUpdate();
        }
    }

    private static String placeholders(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("Identifiers are required");
        }
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private static void verifyClean(Connection connection, String email) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM users WHERE email = ?")) {
            statement.setString(1, email);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                if (result.getLong(1) != 0L) {
                    throw new IllegalStateException("Experiment user still exists after cleanup");
                }
            }
        }
        System.out.println("DB_VERIFY_CLEAN_OK emailAbsent=true");
    }

    private record Accounting(
            int applications,
            int keys,
            long usageTotal,
            long usageUnexpected,
            long usagePerApplicationMismatch,
            long quotaTotal,
            long quotaPerApplicationMismatch,
            long screenshotJobs,
            long shortLinks,
            int expectedSuccesses) {

        boolean complete() {
            return applications == EXPECTED_APPLICATIONS
                    && keys == EXPECTED_APPLICATIONS
                    && usageTotal == expectedSuccesses
                    && usageUnexpected == 0
                    && usagePerApplicationMismatch == 0
                    && quotaTotal == expectedSuccesses
                    && quotaPerApplicationMismatch == 0
                    && screenshotJobs == 0
                    && shortLinks == 0;
        }
    }
}
