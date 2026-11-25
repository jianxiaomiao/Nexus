import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/** JDBC load generator for the isolated Usage index experiment. */
final class UsageIndexTraffic {
    private static final LocalDateTime END = LocalDateTime.parse("2026-11-25T16:00:00");
    private static final String[] API_CODES = {"uuid.generate", "utils.hash", "shortlink.list", "web.extract"};
    private static final int[] WINDOWS = {1, 7, 30};
    private static final String URL = "jdbc:mysql://mysql:3306/nexus_usage_cache_lab"
            + "?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";

    private record Key(long applicationId, long keyId) {
    }

    private record Sample(String type, int slot, long delay, long total, long daily, long byApi) {
    }

    private final List<Key> keys;
    private final List<Key> readKeys;
    private final String dailySql;
    private final String apiSql;
    private final String insertSql;
    private final String countSql;
    private final int seconds;
    private final long eventBatch;

    private UsageIndexTraffic(Path fixtures, String table, int seconds, long eventBatch) throws Exception {
        if (!table.equals("usage_events") && !table.equals("usage_index_cover")) {
            throw new IllegalArgumentException("Unexpected experiment table");
        }
        this.keys = Files.readAllLines(fixtures.resolve("keys.tsv")).stream().map(line -> {
            String[] fields = line.split("\t");
            return new Key(Long.parseLong(fields[0]), Long.parseLong(fields[1]));
        }).toList();
        var firstKeyByApplication = new LinkedHashMap<Long, Key>();
        keys.forEach(key -> firstKeyByApplication.putIfAbsent(key.applicationId(), key));
        this.readKeys = List.copyOf(firstKeyByApplication.values());
        this.dailySql = Files.readString(fixtures.resolve("daily.sql")).replace("usage_events", table);
        this.apiSql = Files.readString(fixtures.resolve("by_api.sql")).replace("usage_events", table);
        this.countSql = "SELECT COUNT(*) FROM " + table + " WHERE api_key_id = ? AND application_id = ?";
        this.insertSql = "INSERT INTO " + table + " (api_key_id, application_id, api_code, http_status_code,"
                + " duration_ms, occurred_at, event_id) VALUES (?, ?, ?, 200, 5, ?, ?)";
        this.seconds = seconds;
        this.eventBatch = eventBatch;
    }

    private Connection open() throws SQLException {
        Connection conn = DriverManager.getConnection(URL, "usage_lab", System.getenv("USAGE_LAB_DB_PASSWORD"));
        conn.setAutoCommit(false);
        conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        return conn;
    }

    private List<Sample> read(int worker, int workers, int rate, CyclicBarrier ready,
                              AtomicLong started) throws Exception {
        var samples = new ArrayList<Sample>();
        try (var conn = open()) {
            conn.setReadOnly(true);
            try (var count = conn.prepareStatement(countSql);
                 var daily = conn.prepareStatement(dailySql);
                 var byApi = conn.prepareStatement(apiSql)) {
                ready.await(20, TimeUnit.SECONDS);
                for (int slot = worker; slot < seconds * rate; slot += workers) {
                    Key key = readKeys.get(slot % readKeys.size());
                    int days = WINDOWS[slot / readKeys.size() % WINDOWS.length];
                    bindKey(count, key);
                    bindWindow(daily, key, days);
                    bindWindow(byApi, key, days);
                    long due = dueAt(started.get(), slot, rate);
                    waitUntil(due);
                    long begin = System.nanoTime();
                    long allTime = sum(count, 1);
                    long groupedAt = System.nanoTime();
                    long byDayCount = sum(daily, 2);
                    long apiAt = System.nanoTime();
                    long apiCount = sum(byApi, 2);
                    long groupedEnd = System.nanoTime();
                    conn.commit();
                    long end = System.nanoTime();
                    if (byDayCount != apiCount || apiCount > allTime) {
                        throw new IllegalStateException("Usage queries disagreed within a snapshot");
                    }
                    samples.add(new Sample("read", slot, begin - due, end - begin,
                            apiAt - groupedAt, groupedEnd - apiAt));
                }
            }
        }
        return samples;
    }

    private List<Sample> write(int worker, int workers, int rate, CyclicBarrier ready,
                               AtomicLong started) throws Exception {
        var samples = new ArrayList<Sample>();
        try (var conn = open();
             var app = conn.prepareStatement("SELECT id FROM applications WHERE id = ? FOR SHARE");
             var key = conn.prepareStatement("SELECT id FROM api_keys WHERE id = ? AND application_id = ? FOR SHARE");
             var insert = conn.prepareStatement(insertSql)) {
            ready.await(20, TimeUnit.SECONDS);
            for (int slot = worker; slot < seconds * rate; slot += workers) {
                Key target = keys.get(slot % keys.size());
                app.setLong(1, target.applicationId());
                bindKey(key, target);
                bindKey(insert, target);
                insert.setString(3, API_CODES[slot % API_CODES.length]);
                insert.setObject(4, END.minusHours(6).plusSeconds(slot));
                insert.setString(5, new UUID(eventBatch, slot + 1L).toString());
                long due = dueAt(started.get(), slot, rate);
                waitUntil(due);
                long begin = System.nanoTime();
                if (sum(app, 1) != target.applicationId() || sum(key, 1) != target.keyId()) {
                    throw new IllegalStateException("Usage write parents not found");
                }
                if (insert.executeUpdate() != 1) {
                    throw new IllegalStateException("Usage insert did not add exactly one row");
                }
                conn.commit();
                samples.add(new Sample("write", slot, begin - due, System.nanoTime() - begin, 0, 0));
            }
        }
        return samples;
    }

    private static void bindKey(PreparedStatement stmt, Key key) throws SQLException {
        stmt.setLong(1, key.keyId());
        stmt.setLong(2, key.applicationId());
    }

    private static void bindWindow(PreparedStatement stmt, Key key, int days) throws SQLException {
        bindKey(stmt, key);
        stmt.setObject(3, END.minusDays(days));
        stmt.setObject(4, END);
    }

    private static long sum(PreparedStatement stmt, int column) throws SQLException {
        long count = 0;
        try (ResultSet rows = stmt.executeQuery()) {
            while (rows.next()) {
                count += rows.getLong(column);
            }
        }
        return count;
    }

    private static long dueAt(long started, int slot, int rate) {
        return started + TimeUnit.SECONDS.toNanos(1) * slot / rate;
    }

    private static void waitUntil(long due) {
        long remaining;
        while ((remaining = due - System.nanoTime()) > 0) {
            LockSupport.parkNanos(remaining);
        }
    }

    private void run(int readRate, int writeRate) throws Exception {
        int readers = Math.min(readRate, 24);
        int writers = Math.min(writeRate, 24);
        var started = new AtomicLong();
        var ready = new CyclicBarrier(readers + writers,
                () -> started.set(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100)));
        try (var executor = Executors.newFixedThreadPool(readers + writers)) {
            var jobs = new ArrayList<Future<List<Sample>>>();
            for (int worker = 0; worker < readers; worker++) {
                int offset = worker;
                jobs.add(executor.submit(() -> read(offset, readers, readRate, ready, started)));
            }
            for (int worker = 0; worker < writers; worker++) {
                int offset = worker;
                jobs.add(executor.submit(() -> write(offset, writers, writeRate, ready, started)));
            }
            for (var job : jobs) {
                for (Sample sample : job.get()) {
                    System.out.printf(Locale.ROOT, "%s\t%d\t%d\t%d\t%d\t%d%n", sample.type(), sample.slot(),
                            sample.delay(), sample.total(), sample.daily(), sample.byApi());
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        new UsageIndexTraffic(Path.of(args[0]), args[1], Integer.parseInt(args[2]), Long.parseLong(args[3]))
                .run(Integer.parseInt(args[4]), Integer.parseInt(args[5]));
    }
}
