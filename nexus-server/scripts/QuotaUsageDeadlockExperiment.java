import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Local nexus_test-only experiment for the suspected Quota/Usage foreign-key
 * lock-order cycle. It never starts Nexus and never changes production code or
 * database privileges.
 */
public final class QuotaUsageDeadlockExperiment {
    private static final String EXPECTED_DATABASE = "nexus_test";
    private static final String EXPECTED_WORKLOAD_USER_PREFIX = "nexus_test_user@";
    private static final DateTimeFormatter RUN_ID_FORMAT =
            DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss-SSS")
                    .withLocale(Locale.ROOT)
                    .withZone(ZoneOffset.UTC);

    private QuotaUsageDeadlockExperiment() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !("preflight".equals(args[0]) || "run".equals(args[0]))) {
            throw new IllegalArgumentException("Expected exactly one action: preflight or run");
        }

        DbSettings workload = DbSettings.fromEnvironment(
                "NEXUS_EXPERIMENT_DB_URL",
                "NEXUS_EXPERIMENT_DB_USERNAME",
                "NEXUS_EXPERIMENT_DB_PASSWORD");
        DbSettings observer = DbSettings.fromOptionalEnvironment(
                "NEXUS_EXPERIMENT_OBSERVER_DB_URL",
                "NEXUS_EXPERIMENT_OBSERVER_DB_USERNAME",
                "NEXUS_EXPERIMENT_OBSERVER_DB_PASSWORD",
                workload);

        PreflightResult preflight = preflight(workload, observer);
        System.out.printf(
                "PREFLIGHT_OK database=%s workloadUser=nexus_test_user observerAccess=innodb-status+data-locks deadlockDetect=%s%n",
                EXPECTED_DATABASE, preflight.deadlockDetect());
        if ("preflight".equals(args[0])) {
            return;
        }

        Path outputRoot = Path.of(optionalEnvironment(
                "NEXUS_EXPERIMENT_OUTPUT_DIR",
                Path.of("target", "quota-usage-deadlock-experiment").toString()));
        String runId = "quota-usage-deadlock-" + RUN_ID_FORMAT.format(Instant.now())
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path runDirectory = outputRoot.resolve(runId).toAbsolutePath().normalize();
        Files.createDirectories(runDirectory);
        write(runDirectory.resolve("preflight.txt"), preflight.describe());

        ExperimentResource resource = null;
        ExperimentResult result = null;
        Throwable failure = null;
        try {
            resource = createResource(workload);
            ControlResult control = runControl(workload, observer, resource, runDirectory);
            TreatmentResult treatment = runTreatment(workload, observer, resource, runDirectory);
            result = new ExperimentResult(runId, resource, control, treatment);
            write(runDirectory.resolve("result.txt"), result.describe());
            System.out.printf("CONTROL_OK waitTable=%s deadlock=false%n", control.waitTable());
            System.out.printf(
                    "TREATMENT_RESULT outcome=%s deadlock=%s usageHeldApiKey=%s waitTable=%s%n",
                    treatment.outcome(), treatment.deadlockObserved(),
                    treatment.usageHeldApiKeyRecordLock(), treatment.waitTable());
        } catch (Throwable throwable) {
            failure = throwable;
            write(runDirectory.resolve("failure.txt"), describeThrowable(throwable));
        } finally {
            if (resource != null) {
                try {
                    CleanupResult cleanup = cleanup(workload, resource);
                    write(runDirectory.resolve("cleanup.txt"), cleanup.describe());
                    System.out.println("CLEANUP_VERIFIED all experiment rows absent");
                } catch (Throwable cleanupFailure) {
                    if (failure == null) {
                        failure = cleanupFailure;
                    } else {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
            System.out.println("RESULT_DIRECTORY=" + runDirectory);
        }
        if (failure != null) {
            if (failure instanceof Exception exception) {
                throw exception;
            }
            throw new RuntimeException(failure);
        }
        if (result == null) {
            throw new IllegalStateException("Experiment did not produce a result");
        }
    }

    private static PreflightResult preflight(DbSettings workload, DbSettings observer)
            throws SQLException {
        try (Connection workloadConnection = workload.connect();
             Connection observerConnection = observer.connect()) {
            DatabaseIdentity workloadIdentity = requireDatabase(workloadConnection, true);
            DatabaseIdentity observerIdentity = requireDatabase(observerConnection, false);
            if (!workloadIdentity.serverUuid().equals(observerIdentity.serverUuid())) {
                throw new IllegalStateException(
                        "Observer and workload connections must target the same MySQL instance");
            }
            boolean deadlockDetect = queryBoolean(observerConnection,
                    "SELECT @@GLOBAL.innodb_deadlock_detect");
            if (!deadlockDetect) {
                throw new IllegalStateException("innodb_deadlock_detect must be enabled");
            }
            String status = readInnoDbStatus(observerConnection);
            if (status.isBlank()) {
                throw new IllegalStateException("SHOW ENGINE INNODB STATUS returned no Status text");
            }
            try (Statement statement = observerConnection.createStatement();
                 ResultSet ignored = statement.executeQuery(
                         "SELECT COUNT(*) FROM performance_schema.data_lock_waits")) {
                if (!ignored.next()) {
                    throw new IllegalStateException("data_lock_waits preflight returned no row");
                }
            }
            try (Statement statement = observerConnection.createStatement();
                 ResultSet ignored = statement.executeQuery(
                         "SELECT COUNT(*) FROM performance_schema.data_locks")) {
                if (!ignored.next()) {
                    throw new IllegalStateException("data_locks preflight returned no row");
                }
            }
            return new PreflightResult(
                    workloadIdentity.database(),
                    observerIdentity.database() == null ? "<none>" : observerIdentity.database(),
                    workloadIdentity.serverUuid(), deadlockDetect,
                    workloadIdentity.currentUser().startsWith(EXPECTED_WORKLOAD_USER_PREFIX),
                    true, true);
        } catch (SQLException exception) {
            if (exception.getErrorCode() == 1227 || "42000".equals(exception.getSQLState())) {
                throw new IllegalStateException(
                        "Observer cannot read InnoDB status/lock tables. Do not grant PROCESS "
                                + "to the Nexus runtime account; provide a separate local-only "
                                + "observer connection through NEXUS_EXPERIMENT_OBSERVER_DB_*.",
                        exception);
            }
            throw exception;
        }
    }

    private static DatabaseIdentity requireDatabase(Connection connection, boolean workload)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT DATABASE(), CURRENT_USER()")) {
            if (!result.next()) {
                throw new IllegalStateException("Database identity query returned no row");
            }
            String database = result.getString(1);
            String currentUser = result.getString(2);
            if (workload && !EXPECTED_DATABASE.equals(database)) {
                throw new IllegalStateException("Refusing database other than nexus_test");
            }
            if (!workload && database != null && !EXPECTED_DATABASE.equals(database)) {
                throw new IllegalStateException(
                        "Observer connection may select no schema or nexus_test only");
            }
            if (workload && (currentUser == null
                    || !currentUser.startsWith(EXPECTED_WORKLOAD_USER_PREFIX))) {
                throw new IllegalStateException(
                        "Workload connection must use the dedicated nexus_test_user");
            }
            String serverUuid;
            try (Statement uuidStatement = connection.createStatement();
                 ResultSet uuidResult = uuidStatement.executeQuery("SELECT @@server_uuid")) {
                uuidResult.next();
                serverUuid = uuidResult.getString(1);
            }
            return new DatabaseIdentity(database, currentUser, serverUuid);
        }
    }

    private static ExperimentResource createResource(DbSettings workload) throws SQLException {
        String marker = UUID.randomUUID().toString();
        String email = "quota-usage-deadlock-" + marker + "@example.test";
        try (Connection connection = workload.connect()) {
            connection.setAutoCommit(false);
            try {
                long userId = insertReturningId(connection, """
                        INSERT INTO users(email, display_name, password_hash)
                        VALUES (?, 'Quota Usage deadlock experiment', 'test-only-placeholder')
                        """, email);
                long applicationId = insertReturningId(connection, """
                        INSERT INTO applications(owner_user_id, name)
                        VALUES (?, ?)
                        """, userId, "deadlock-" + marker);
                String publicId = UUID.randomUUID().toString();
                String secretHash = UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", "");
                long apiKeyId = insertReturningId(connection, """
                        INSERT INTO api_keys(application_id, name, public_id, secret_hash, key_preview)
                        VALUES (?, 'deadlock-key', ?, ?, 'nxk_dead******lock')
                        """, applicationId, publicId, secretHash);
                connection.commit();
                return new ExperimentResource(userId, applicationId, apiKeyId, email);
            } catch (Throwable throwable) {
                rollbackQuietly(connection);
                throw throwable;
            }
        }
    }

    private static ControlResult runControl(
            DbSettings workload, DbSettings observer, ExperimentResource resource,
            Path runDirectory) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection quota = workload.connect();
             Connection usage = workload.connect();
             Connection observation = observer.connect()) {
            prepareTransaction(quota);
            prepareTransaction(usage);
            long quotaConnectionId = connectionId(quota);
            long usageConnectionId = connectionId(usage);
            lockApplication(quota, resource.applicationId());
            lockApiKey(quota, resource.applicationId(), resource.apiKeyId());

            Future<Integer> usageInsert = executor.submit(
                    () -> insertUsage(usage, resource, UUID.randomUUID().toString()));
            List<WaitRow> waits = waitForLockWait(
                    observation, usageConnectionId, quotaConnectionId, Duration.ofSeconds(3));
            write(runDirectory.resolve("control-lock-wait.txt"), describeWaits(waits));
            String waitTable = waits.getFirst().requestingObject();

            quota.rollback();
            int inserted = getFuture(usageInsert, Duration.ofSeconds(3));
            if (inserted != 1) {
                throw new IllegalStateException("Control Usage insert did not affect one row");
            }
            usage.rollback();
            assertNoUsageRows(workload, resource.applicationId());
            return new ControlResult(waitTable, waits.size(), false);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    private static TreatmentResult runTreatment(
            DbSettings workload, DbSettings observer, ExperimentResource resource,
            Path runDirectory) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection quota = workload.connect();
             Connection usage = workload.connect();
             Connection observation = observer.connect()) {
            prepareTransaction(quota);
            prepareTransaction(usage);
            long quotaConnectionId = connectionId(quota);
            long usageConnectionId = connectionId(usage);
            lockApplication(quota, resource.applicationId());

            Future<Integer> usageInsert = executor.submit(
                    () -> insertUsage(usage, resource, UUID.randomUUID().toString()));
            List<WaitRow> waits = waitForLockWait(
                    observation, usageConnectionId, quotaConnectionId, Duration.ofSeconds(3));
            String waitTable = waits.getFirst().requestingObject();
            List<LockRow> locksBeforeCycle = readLocks(
                    observation, quotaConnectionId, usageConnectionId);
            write(runDirectory.resolve("treatment-locks-before-cycle.txt"),
                    describeLocks(waits, locksBeforeCycle));
            boolean usageHeldApiKeyRecordLock = locksBeforeCycle.stream().anyMatch(lock ->
                    lock.connectionId() == usageConnectionId
                            && "api_keys".equals(lock.objectName())
                            && "RECORD".equals(lock.lockType())
                            && "GRANTED".equals(lock.lockStatus())
                            && lock.lockMode().contains("S"));

            boolean deadlockObserved = false;
            SQLException deadlock = null;
            boolean quotaKeyLockReturned = false;
            try {
                lockApiKey(quota, resource.applicationId(), resource.apiKeyId());
                quotaKeyLockReturned = true;
            } catch (SQLException exception) {
                if (!isDeadlock(exception)) {
                    throw exception;
                }
                deadlockObserved = true;
                deadlock = exception;
            }

            if (deadlockObserved) {
                saveDeadlockStatus(observation, runDirectory, quotaConnectionId, usageConnectionId);
                rollbackQuietly(quota);
                getFutureAllowingDeadlock(usageInsert, Duration.ofSeconds(3));
            } else if (quotaKeyLockReturned) {
                try {
                    getFuture(usageInsert, Duration.ofMillis(500));
                } catch (ExecutionException exception) {
                    SQLException sqlException = findSQLException(exception);
                    if (sqlException == null || !isDeadlock(sqlException)) {
                        throw exception;
                    }
                    deadlockObserved = true;
                    deadlock = sqlException;
                    saveDeadlockStatus(
                            observation, runDirectory, quotaConnectionId, usageConnectionId);
                } catch (TimeoutException noCycleYet) {
                    // The Usage INSERT is still waiting only on the Application lock.
                }
                rollbackQuietly(quota);
                if (!usageInsert.isDone()) {
                    getFuture(usageInsert, Duration.ofSeconds(3));
                }
            }

            rollbackQuietly(quota);
            rollbackQuietly(usage);
            assertNoUsageRows(workload, resource.applicationId());
            String outcome;
            if (deadlockObserved && usageHeldApiKeyRecordLock
                    && "applications".equals(waitTable)) {
                outcome = "LOCK_ORDER_HYPOTHESIS_CONFIRMED";
            } else if (deadlockObserved) {
                outcome = "DEADLOCK_OBSERVED_PATH_NOT_FULLY_CONFIRMED";
            } else {
                outcome = "LOCK_ORDER_HYPOTHESIS_NOT_REPRODUCED";
            }
            return new TreatmentResult(
                    outcome, waitTable, usageHeldApiKeyRecordLock,
                    deadlockObserved, deadlock == null ? null : deadlock.getErrorCode(),
                    deadlock == null ? null : deadlock.getSQLState(),
                    quotaConnectionId, usageConnectionId);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    private static void saveDeadlockStatus(
            Connection observer, Path runDirectory,
            long quotaConnectionId, long usageConnectionId) throws SQLException, IOException {
        String status = readInnoDbStatus(observer);
        Path path = runDirectory.resolve("innodb-status-after-deadlock.txt");
        write(path, status);
        if (!status.contains("LATEST DETECTED DEADLOCK")) {
            throw new IllegalStateException("InnoDB status did not contain a deadlock section");
        }
        String quotaMarker = "MySQL thread id " + quotaConnectionId;
        String usageMarker = "MySQL thread id " + usageConnectionId;
        if (!status.contains(quotaMarker) || !status.contains(usageMarker)) {
            throw new IllegalStateException(
                    "Latest deadlock report does not contain both experiment connection IDs");
        }
    }

    private static List<WaitRow> waitForLockWait(
            Connection observer, long requestingConnectionId, long blockingConnectionId,
            Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            List<WaitRow> waits = readWaits(
                    observer, requestingConnectionId, blockingConnectionId);
            if (!waits.isEmpty()) {
                return waits;
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException(
                "Usage INSERT did not enter the expected observable lock wait");
    }

    private static List<WaitRow> readWaits(
            Connection observer, long requestingConnectionId, long blockingConnectionId)
            throws SQLException {
        String sql = """
                SELECT rt.PROCESSLIST_ID,
                       bt.PROCESSLIST_ID,
                       req.OBJECT_SCHEMA,
                       req.OBJECT_NAME,
                       req.LOCK_TYPE,
                       req.LOCK_MODE,
                       req.LOCK_STATUS,
                       req.LOCK_DATA,
                       blk.OBJECT_NAME,
                       blk.LOCK_TYPE,
                       blk.LOCK_MODE,
                       blk.LOCK_STATUS,
                       blk.LOCK_DATA
                FROM performance_schema.data_lock_waits waits
                JOIN performance_schema.data_locks req
                  ON req.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
                JOIN performance_schema.data_locks blk
                  ON blk.ENGINE_LOCK_ID = waits.BLOCKING_ENGINE_LOCK_ID
                JOIN performance_schema.threads rt
                  ON rt.THREAD_ID = req.THREAD_ID
                JOIN performance_schema.threads bt
                  ON bt.THREAD_ID = blk.THREAD_ID
                WHERE rt.PROCESSLIST_ID = ?
                  AND bt.PROCESSLIST_ID = ?
                  AND req.OBJECT_SCHEMA = ?
                ORDER BY req.OBJECT_NAME, req.LOCK_TYPE, req.LOCK_MODE
                """;
        try (PreparedStatement statement = observer.prepareStatement(sql)) {
            statement.setLong(1, requestingConnectionId);
            statement.setLong(2, blockingConnectionId);
            statement.setString(3, EXPECTED_DATABASE);
            try (ResultSet result = statement.executeQuery()) {
                List<WaitRow> rows = new ArrayList<>();
                while (result.next()) {
                    rows.add(new WaitRow(
                            result.getLong(1), result.getLong(2), result.getString(3),
                            result.getString(4), result.getString(5), result.getString(6),
                            result.getString(7), result.getString(8), result.getString(9),
                            result.getString(10), result.getString(11), result.getString(12),
                            result.getString(13)));
                }
                return rows;
            }
        }
    }

    private static List<LockRow> readLocks(
            Connection observer, long firstConnectionId, long secondConnectionId)
            throws SQLException {
        String sql = """
                SELECT threads.PROCESSLIST_ID,
                       locks.OBJECT_SCHEMA,
                       locks.OBJECT_NAME,
                       locks.LOCK_TYPE,
                       locks.LOCK_MODE,
                       locks.LOCK_STATUS,
                       locks.LOCK_DATA
                FROM performance_schema.data_locks locks
                JOIN performance_schema.threads threads
                  ON threads.THREAD_ID = locks.THREAD_ID
                WHERE threads.PROCESSLIST_ID IN (?, ?)
                  AND locks.OBJECT_SCHEMA = ?
                ORDER BY threads.PROCESSLIST_ID, locks.OBJECT_NAME,
                         locks.LOCK_TYPE, locks.LOCK_MODE, locks.LOCK_DATA
                """;
        try (PreparedStatement statement = observer.prepareStatement(sql)) {
            statement.setLong(1, firstConnectionId);
            statement.setLong(2, secondConnectionId);
            statement.setString(3, EXPECTED_DATABASE);
            try (ResultSet result = statement.executeQuery()) {
                List<LockRow> rows = new ArrayList<>();
                while (result.next()) {
                    rows.add(new LockRow(
                            result.getLong(1), result.getString(2), result.getString(3),
                            result.getString(4), result.getString(5), result.getString(6),
                            result.getString(7)));
                }
                return rows;
            }
        }
    }

    private static String describeWaits(List<WaitRow> waits) {
        StringBuilder builder = new StringBuilder();
        for (WaitRow wait : waits) {
            builder.append(wait).append(System.lineSeparator());
        }
        return builder.toString();
    }

    private static String describeLocks(List<WaitRow> waits, List<LockRow> locks) {
        StringBuilder builder = new StringBuilder("WAITS\n");
        builder.append(describeWaits(waits));
        builder.append("LOCKS\n");
        for (LockRow lock : locks) {
            builder.append(lock).append(System.lineSeparator());
        }
        return builder.toString();
    }

    private static void prepareTransaction(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION innodb_lock_wait_timeout = 5");
        }
    }

    private static void lockApplication(Connection connection, long applicationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM applications WHERE id = ? FOR UPDATE")) {
            statement.setLong(1, applicationId);
            requireOneRow(statement, "Application lock target is missing");
        }
    }

    private static void lockApiKey(
            Connection connection, long applicationId, long apiKeyId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM api_keys WHERE id = ? AND application_id = ? FOR UPDATE")) {
            statement.setLong(1, apiKeyId);
            statement.setLong(2, applicationId);
            requireOneRow(statement, "API Key lock target is missing");
        }
    }

    private static void requireOneRow(PreparedStatement statement, String message)
            throws SQLException {
        try (ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new IllegalStateException(message);
            }
        }
    }

    private static int insertUsage(
            Connection connection, ExperimentResource resource, String eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO usage_events(
                    api_key_id, application_id, api_code, http_status_code,
                    duration_ms, occurred_at, event_id)
                VALUES (?, ?, 'utils.uuid', 200, 1, UTC_TIMESTAMP(3), ?)
                """)) {
            statement.setLong(1, resource.apiKeyId());
            statement.setLong(2, resource.applicationId());
            statement.setString(3, eventId);
            return statement.executeUpdate();
        }
    }

    private static long connectionId(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT CONNECTION_ID()")) {
            result.next();
            return result.getLong(1);
        }
    }

    private static String readInnoDbStatus(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SHOW ENGINE INNODB STATUS")) {
            if (!result.next()) {
                throw new IllegalStateException("SHOW ENGINE INNODB STATUS returned no row");
            }
            return result.getString("Status");
        }
    }

    private static boolean queryBoolean(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                throw new IllegalStateException("Boolean query returned no row");
            }
            return result.getBoolean(1);
        }
    }

    private static int getFuture(Future<Integer> future, Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static void getFutureAllowingDeadlock(Future<Integer> future, Duration timeout)
            throws Exception {
        try {
            getFuture(future, timeout);
        } catch (ExecutionException exception) {
            SQLException sqlException = findSQLException(exception);
            if (sqlException == null || !isDeadlock(sqlException)) {
                throw exception;
            }
        }
    }

    private static SQLException findSQLException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        return null;
    }

    private static boolean isDeadlock(SQLException exception) {
        return exception.getErrorCode() == 1213 || "40001".equals(exception.getSQLState());
    }

    private static long insertReturningId(
            Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Insert did not affect exactly one row");
            }
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new IllegalStateException("Insert did not return a generated key");
                }
                return keys.getLong(1);
            }
        }
    }

    private static void assertNoUsageRows(DbSettings workload, long applicationId)
            throws SQLException {
        try (Connection connection = workload.connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM usage_events WHERE application_id = ?")) {
            statement.setLong(1, applicationId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                if (result.getLong(1) != 0) {
                    throw new IllegalStateException("Rolled-back Usage rows remain visible");
                }
            }
        }
    }

    private static CleanupResult cleanup(DbSettings workload, ExperimentResource resource)
            throws SQLException {
        try (Connection connection = workload.connect()) {
            connection.setAutoCommit(false);
            try {
                requireExactResource(connection, resource);
                int screenshotJobs = delete(connection,
                        "DELETE FROM screenshot_jobs WHERE api_key_id = ?", resource.apiKeyId());
                int shortLinks = delete(connection,
                        "DELETE FROM short_links WHERE api_key_id = ?", resource.apiKeyId());
                int usageEvents = delete(connection,
                        "DELETE FROM usage_events WHERE application_id = ?",
                        resource.applicationId());
                int quotas = delete(connection,
                        "DELETE FROM application_quotas WHERE application_id = ?",
                        resource.applicationId());
                int keys = delete(connection,
                        "DELETE FROM api_keys WHERE id = ? AND application_id = ?",
                        resource.apiKeyId(), resource.applicationId());
                int applications = delete(connection,
                        "DELETE FROM applications WHERE id = ? AND owner_user_id = ?",
                        resource.applicationId(), resource.userId());
                int users = delete(connection,
                        "DELETE FROM users WHERE id = ? AND email = ?",
                        resource.userId(), resource.email());
                if (keys != 1 || applications != 1 || users != 1) {
                    throw new IllegalStateException(
                            "Cleanup affected an unexpected number of parent rows");
                }
                connection.commit();
                verifyAbsent(connection, resource);
                return new CleanupResult(
                        screenshotJobs, shortLinks, usageEvents, quotas, keys,
                        applications, users, true);
            } catch (Throwable throwable) {
                rollbackQuietly(connection);
                throw throwable;
            }
        }
    }

    private static void requireExactResource(
            Connection connection, ExperimentResource resource) throws SQLException {
        String sql = """
                SELECT COUNT(*)
                FROM applications application
                JOIN users owner ON owner.id = application.owner_user_id
                JOIN api_keys api_key ON api_key.application_id = application.id
                WHERE owner.id = ? AND owner.email = ?
                  AND application.id = ? AND api_key.id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, resource.userId());
            statement.setString(2, resource.email());
            statement.setLong(3, resource.applicationId());
            statement.setLong(4, resource.apiKeyId());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                if (result.getInt(1) != 1) {
                    throw new IllegalStateException(
                            "Cleanup resource identity does not match this experiment");
                }
            }
        }
    }

    private static void verifyAbsent(Connection connection, ExperimentResource resource)
            throws SQLException {
        String sql = """
                SELECT
                    (SELECT COUNT(*) FROM usage_events WHERE application_id = ?),
                    (SELECT COUNT(*) FROM application_quotas WHERE application_id = ?),
                    (SELECT COUNT(*) FROM api_keys WHERE id = ?),
                    (SELECT COUNT(*) FROM applications WHERE id = ?),
                    (SELECT COUNT(*) FROM users WHERE id = ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, resource.applicationId());
            statement.setLong(2, resource.applicationId());
            statement.setLong(3, resource.apiKeyId());
            statement.setLong(4, resource.applicationId());
            statement.setLong(5, resource.userId());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                for (int index = 1; index <= 5; index++) {
                    if (result.getLong(index) != 0) {
                        throw new IllegalStateException(
                                "Experiment resource remains after cleanup");
                    }
                }
            }
        }
    }

    private static int delete(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            return statement.executeUpdate();
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            if (connection != null && !connection.getAutoCommit()) {
                connection.rollback();
            }
        } catch (SQLException ignored) {
            // The primary experiment error is more useful than a cleanup rollback error.
        }
    }

    private static String describeThrowable(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            builder.append(current.getClass().getName())
                    .append(": ")
                    .append(current.getMessage())
                    .append(System.lineSeparator());
            current = current.getCause();
        }
        return builder.toString();
    }

    private static void write(Path path, String text) throws IOException {
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private static String optionalEnvironment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private record DbSettings(String url, String username, String password) {
        static DbSettings fromEnvironment(
                String urlName, String usernameName, String passwordName) {
            return new DbSettings(
                    requireEnvironment(urlName),
                    requireEnvironment(usernameName),
                    requireEnvironment(passwordName));
        }

        static DbSettings fromOptionalEnvironment(
                String urlName, String usernameName, String passwordName,
                DbSettings fallback) {
            return new DbSettings(
                    optionalEnvironment(urlName, fallback.url()),
                    optionalEnvironment(usernameName, fallback.username()),
                    optionalEnvironment(passwordName, fallback.password()));
        }

        Connection connect() throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }
    }

    private record DatabaseIdentity(String database, String currentUser, String serverUuid) {
    }

    private record PreflightResult(
            String workloadDatabase,
            String observerDatabase,
            String serverUuid,
            boolean deadlockDetect,
            boolean dedicatedWorkloadUser,
            boolean innodbStatusReadable,
            boolean dataLocksReadable) {
        String describe() {
            return "workloadDatabase=" + workloadDatabase + "\n"
                    + "observerDatabase=" + observerDatabase + "\n"
                    + "sameServerUuid=" + (serverUuid != null && !serverUuid.isBlank()) + "\n"
                    + "deadlockDetect=" + deadlockDetect + "\n"
                    + "dedicatedWorkloadUser=" + dedicatedWorkloadUser + "\n"
                    + "innodbStatusReadable=" + innodbStatusReadable + "\n"
                    + "dataLocksReadable=" + dataLocksReadable + "\n";
        }
    }

    private record ExperimentResource(
            long userId, long applicationId, long apiKeyId, String email) {
    }

    private record WaitRow(
            long requestingConnectionId,
            long blockingConnectionId,
            String objectSchema,
            String requestingObject,
            String requestingLockType,
            String requestingLockMode,
            String requestingLockStatus,
            String requestingLockData,
            String blockingObject,
            String blockingLockType,
            String blockingLockMode,
            String blockingLockStatus,
            String blockingLockData) {
    }

    private record LockRow(
            long connectionId,
            String objectSchema,
            String objectName,
            String lockType,
            String lockMode,
            String lockStatus,
            String lockData) {
    }

    private record ControlResult(String waitTable, int observedWaitRows, boolean deadlock) {
    }

    private record TreatmentResult(
            String outcome,
            String waitTable,
            boolean usageHeldApiKeyRecordLock,
            boolean deadlockObserved,
            Integer errorCode,
            String sqlState,
            long quotaConnectionId,
            long usageConnectionId) {
    }

    private record ExperimentResult(
            String runId,
            ExperimentResource resource,
            ControlResult control,
            TreatmentResult treatment) {
        String describe() {
            return "runId=" + runId + "\n"
                    + "applicationId=" + resource.applicationId() + "\n"
                    + "apiKeyId=" + resource.apiKeyId() + "\n"
                    + "controlWaitTable=" + control.waitTable() + "\n"
                    + "controlObservedWaitRows=" + control.observedWaitRows() + "\n"
                    + "controlDeadlock=" + control.deadlock() + "\n"
                    + "treatmentOutcome=" + treatment.outcome() + "\n"
                    + "treatmentWaitTable=" + treatment.waitTable() + "\n"
                    + "usageHeldApiKeyRecordLock="
                    + treatment.usageHeldApiKeyRecordLock() + "\n"
                    + "deadlockObserved=" + treatment.deadlockObserved() + "\n"
                    + "deadlockErrorCode=" + treatment.errorCode() + "\n"
                    + "deadlockSqlState=" + treatment.sqlState() + "\n"
                    + "quotaConnectionId=" + treatment.quotaConnectionId() + "\n"
                    + "usageConnectionId=" + treatment.usageConnectionId() + "\n";
        }
    }

    private record CleanupResult(
            int screenshotJobs,
            int shortLinks,
            int usageEvents,
            int quotas,
            int apiKeys,
            int applications,
            int users,
            boolean verified) {
        String describe() {
            return "screenshotJobs=" + screenshotJobs + "\n"
                    + "shortLinks=" + shortLinks + "\n"
                    + "usageEvents=" + usageEvents + "\n"
                    + "quotas=" + quotas + "\n"
                    + "apiKeys=" + apiKeys + "\n"
                    + "applications=" + applications + "\n"
                    + "users=" + users + "\n"
                    + "verified=" + verified + "\n";
        }
    }
}
