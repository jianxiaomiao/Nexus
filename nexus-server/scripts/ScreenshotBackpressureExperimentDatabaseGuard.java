import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Database identity guard and exact cleanup helper for the controlled
 * screenshot backpressure experiment. Credentials are read only from the
 * process environment and are never printed.
 */
public final class ScreenshotBackpressureExperimentDatabaseGuard {
    private static final String EXPECTED_DATABASE = "nexus_test";
    private static final String EXPECTED_USER_PREFIX = "nexus_test_user@";
    private static final String EMAIL_PREFIX = "obs-screenshot-";
    private static final String APPLICATION_PREFIX = "obs-screenshot-";
    private static final String KEY_PREFIX = "obs-screenshot-key-";

    private ScreenshotBackpressureExperimentDatabaseGuard() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("Expected preflight, cleanup or verify-clean");
        }
        try (Connection connection = connect()) {
            requireDedicatedDatabase(connection);
            switch (args[0]) {
                case "preflight" -> System.out.println(
                        "DB_PREFLIGHT_OK database=nexus_test user=nexus_test_user");
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
        if (args.length != 2 || !args[1].startsWith(EMAIL_PREFIX)
                || !args[1].endsWith("@example.test")) {
            throw new IllegalArgumentException("Expected exact experiment email");
        }
        return args[1];
    }

    private static void cleanup(Connection connection, String email) throws SQLException {
        connection.setAutoCommit(false);
        try {
            Long userId = selectUserForUpdate(connection, email);
            if (userId == null) {
                connection.commit();
                System.out.println("DB_CLEANUP_OK alreadyAbsent=true");
                return;
            }
            List<Long> applicationIds = selectAndVerifyApplications(connection, userId);
            List<Long> keyIds = selectAndVerifyKeys(connection, applicationIds);

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

    private static Long selectUserForUpdate(Connection connection, String email)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM users WHERE email = ? FOR UPDATE")) {
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
            Connection connection, long userId) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, name FROM applications WHERE owner_user_id = ? FOR UPDATE")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String name = result.getString(2);
                    if (name == null || !name.startsWith(APPLICATION_PREFIX)) {
                        throw new IllegalStateException(
                                "Refusing cleanup of a non-experiment Application");
                    }
                    ids.add(result.getLong(1));
                }
            }
        }
        return ids;
    }

    private static List<Long> selectAndVerifyKeys(
            Connection connection, List<Long> applicationIds) throws SQLException {
        if (applicationIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        String sql = "SELECT id, name FROM api_keys WHERE application_id IN ("
                + placeholders(applicationIds.size()) + ") FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindIds(statement, applicationIds);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String name = result.getString(2);
                    if (name == null || !name.startsWith(KEY_PREFIX)) {
                        throw new IllegalStateException(
                                "Refusing cleanup of a non-experiment API Key");
                    }
                    ids.add(result.getLong(1));
                }
            }
        }
        return ids;
    }

    private static int deleteByIds(
            Connection connection, String template, List<Long> ids) throws SQLException {
        if (ids.isEmpty()) {
            return 0;
        }
        String sql = template.formatted(placeholders(ids.size()));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindIds(statement, ids);
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
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static void bindIds(PreparedStatement statement, List<Long> ids)
            throws SQLException {
        for (int index = 0; index < ids.size(); index++) {
            statement.setLong(index + 1, ids.get(index));
        }
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
}
