package cc4p1.clients.cli;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LoadTestReporter {
    public static final class ResultRow {
        public final long startNs;
        public final long endNs;
        public final long latencyMicros;
        public final int statusCode;
        public final String operation;
        public final String id;
        public final double amount;
        public final String extra;

        public ResultRow(long startNs, long endNs, int statusCode, String operation, String id, double amount,
                String extra) {
            this.startNs = startNs;
            this.endNs = endNs;
            this.latencyMicros = Math.max(0L, (endNs - startNs) / 1_000);
            this.statusCode = statusCode;
            this.operation = operation;
            this.id = id;
            this.amount = amount;
            this.extra = extra == null ? "" : extra.replace('\n', ' ').replace('\r', ' ');
        }
    }

    public static final class Summary {
        public final int total;
        public final int ok;
        public final int fail;
        public final long p50;
        public final long p90;
        public final long p95;
        public final long p99;
        public final double qps;

        public Summary(int total, int ok, int fail, long p50, long p90, long p95, long p99, double qps) {
            this.total = total;
            this.ok = ok;
            this.fail = fail;
            this.p50 = p50;
            this.p90 = p90;
            this.p95 = p95;
            this.p99 = p99;
            this.qps = qps;
        }
    }

    private final List<ResultRow> rows = Collections.synchronizedList(new ArrayList<>());
    private volatile long startNs;
    private volatile long endNs;

    public void start() {
        this.startNs = System.nanoTime();
    }

    public void stop() {
        this.endNs = System.nanoTime();
    }

    public void add(ResultRow row) {
        rows.add(row);
    }

    public Summary summarize() {
        List<Long> lats = new ArrayList<>(rows.size());
        int ok = 0;
        for (ResultRow r : rows) {
            lats.add(r.latencyMicros);
            if (r.statusCode >= 200 && r.statusCode < 300)
                ok++;
        }
        Collections.sort(lats);
        long p50 = pct(lats, 50);
        long p90 = pct(lats, 90);
        long p95 = pct(lats, 95);
        long p99 = pct(lats, 99);
        int total = rows.size();
        int fail = total - ok;
        double durSec = Math.max(1e-9, (endNs - startNs) / 1_000_000_000.0);
        double qps = total / durSec;
        return new Summary(total, ok, fail, p50, p90, p95, p99, qps);
    }

    private static long pct(List<Long> xs, int p) {
        if (xs.isEmpty())
            return 0L;
        int idx = Math.min(xs.size() - 1, Math.max(0, (int) Math.round((p / 100.0) * (xs.size() - 1))));
        return xs.get(idx);
    }

    public void writeCsv(File file) throws IOException {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(file, StandardCharsets.UTF_8))) {
            bw.write("startNs,endNs,latencyMicros,status,operation,id,amount,extra\n");
            for (ResultRow r : rows) {
                bw.write(r.startNs + "," + r.endNs + "," + r.latencyMicros + "," + r.statusCode + "," +
                        safe(r.operation) + "," + safe(r.id) + "," + r.amount + "," + safe(r.extra) + "\n");
            }
        }
    }

    private static String safe(String s) {
        if (s == null)
            return "";
        String v = s.replace('"', '\'');
        if (v.contains(","))
            return '"' + v + '"';
        return v;
    }
}
