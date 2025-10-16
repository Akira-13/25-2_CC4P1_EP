package cc4p1.clients.cli;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Generador de carga: N hilos, distribución de montos, delays aleatorios y
 * reporte CSV.
 * Operaciones soportadas: transferir y crear_prestamo (básicas para
 * smoke/load).
 */
public final class LoadGenerator {
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || has(args, "-h", "--help")) {
            System.out.println(
                    "Uso: LoadGenerator --coordinator=host:port --threads=N --durationSec=S --ops=transfer|loan|mixed --rate=Rps --min=MIN --max=MAX --delayMsMin=A --delayMsMax=B --out=results.csv --from=ID --to=ID --accountRange=MIN:MAX [--idClienteRange=MIN:MAX | --idCliente=ID] [--tasaAnual=0.25] [--loanTarget=coordinator|worker]");
            return;
        }

        String coord = opt(args, "--coordinator", "localhost:8080");
        int threads = Integer.parseInt(opt(args, "--threads", "4"));
        int durationSec = Integer.parseInt(opt(args, "--durationSec", "20"));
        String ops = opt(args, "--ops", "transfer");
        double rate = Double.parseDouble(opt(args, "--rate", "0")); // 0 = sin control (lo más rápido posible)
        double min = Double.parseDouble(opt(args, "--min", "10"));
        double max = Double.parseDouble(opt(args, "--max", "100"));
        long dMin = Long.parseLong(opt(args, "--delayMsMin", "0"));
        long dMax = Long.parseLong(opt(args, "--delayMsMax", "0"));
        String out = opt(args, "--out", "load_results.csv");
        long fromFixed = Long.parseLong(opt(args, "--from", "0"));
        long toFixed = Long.parseLong(opt(args, "--to", "0"));
        String accRange = opt(args, "--accountRange", "0:0");
        String idcRange = opt(args, "--idClienteRange", "0:0");
        long idClienteFixed = Long.parseLong(opt(args, "--idCliente", "0"));
        String tasaAnualStr = opt(args, "--tasaAnual", "0.25");
        String target = opt(args, "--loanTarget", "coordinator"); // coordinator|worker
        final long[] accRangeBox = new long[] { 0L, 0L };
        if (accRange.contains(":")) {
            String[] p = accRange.split(":");
            accRangeBox[0] = Long.parseLong(p[0]);
            accRangeBox[1] = Long.parseLong(p[1]);
        }
        final long[] idcRangeBox = new long[] { 0L, 0L };
        if (idcRange.contains(":")) {
            String[] p = idcRange.split(":");
            idcRangeBox[0] = Long.parseLong(p[0]);
            idcRangeBox[1] = Long.parseLong(p[1]);
        }

        var dist = DistributionUtils.create();
        var reporter = new LoadTestReporter();
        reporter.start();

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);

        Runnable task = () -> {
            long endAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(durationSec);
            try {
                while (System.nanoTime() < endAt) {
                    double amount = Math.max(0.01, dist.uniform(min, max));
                    long start = System.nanoTime();
                    int code = 0;
                    String op = "";
                    String id = "";
                    String extra = "";

                    try {
                        String which = pickOp(ops);
                        if (which.equals("transfer")) {
                            long from = fromFixed > 0 ? fromFixed
                                    : (accRangeBox[0] > 0 && accRangeBox[1] > 0
                                            ? dist.uniformLong(accRangeBox[0], accRangeBox[1])
                                            : 1);
                            long to = toFixed > 0 ? toFixed
                                    : (accRangeBox[0] > 0 && accRangeBox[1] > 0
                                            ? dist.uniformLong(accRangeBox[0], accRangeBox[1])
                                            : 2);
                            if (to == from)
                                to = from + 1;
                            String txId = UUID.randomUUID().toString();
                            op = "transfer";
                            id = txId;
                            String url = String.format(Locale.ROOT,
                                    "http://%s/transferir_cuenta?origen=%d&destino=%d&monto=%s&txId=%s", coord, from,
                                    to, String.format(Locale.ROOT, "%.2f", amount), txId);
                            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                                    .POST(HttpRequest.BodyPublishers.noBody()).build();
                            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                            code = resp.statusCode();
                            extra = trim(resp.body());
                        } else if (which.equals("loan")) {
                            long loanId = System.currentTimeMillis();
                            op = "loan";
                            id = String.valueOf(loanId);
                            String url;
                            if ("worker".equalsIgnoreCase(target)) {
                                long account = fromFixed > 0 ? fromFixed
                                        : (accRangeBox[0] > 0 && accRangeBox[1] > 0
                                                ? dist.uniformLong(accRangeBox[0], accRangeBox[1])
                                                : 1);
                                url = String.format(Locale.ROOT,
                                        "http://%s/crear_prestamo?cuentaId=%d&monto=%s&loanId=%d", coord, account,
                                        String.format(Locale.ROOT, "%.2f", amount), loanId);
                            } else {
                                long idCliente = idClienteFixed > 0 ? idClienteFixed
                                        : (idcRangeBox[0] > 0 && idcRangeBox[1] > 0
                                                ? dist.uniformLong(idcRangeBox[0], idcRangeBox[1])
                                                : (accRangeBox[0] > 0 && accRangeBox[1] > 0
                                                        ? dist.uniformLong(accRangeBox[0], accRangeBox[1])
                                                        : 1));
                                url = String.format(Locale.ROOT,
                                        "http://%s/crear_prestamo?idCliente=%d&monto=%s&tasaAnual=%s&loanId=%d",
                                        coord, idCliente, String.format(Locale.ROOT, "%.2f", amount), tasaAnualStr,
                                        loanId);
                            }
                            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                                    .POST(HttpRequest.BodyPublishers.noBody()).build();
                            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                            code = resp.statusCode();
                            extra = trim(resp.body());
                        }
                    } catch (Exception e) {
                        code = 599; // network/client error
                        extra = e.getClass().getSimpleName() + ": " + e.getMessage();
                    } finally {
                        long end = System.nanoTime();
                        reporter.add(new LoadTestReporter.ResultRow(start, end, code, op, id, amount, extra));
                    }

                    if (dMax > 0) {
                        long d = dMin >= dMax ? dMin : (long) Math.floor(dist.uniform(dMin, dMax));
                        sleep(d);
                    }

                    if (rate > 0) {
                        // ritmo aproximado: dormir para no superar RPS por thread
                        double perReqMs = 1000.0 / rate;
                        sleep((long) perReqMs);
                    }
                }
            } finally {
                done.countDown();
            }
        };

        for (int i = 0; i < threads; i++)
            pool.submit(task);
        done.await();
        pool.shutdownNow();
        reporter.stop();

        var summary = reporter.summarize();
        System.out.printf("Total=%d OK=%d FAIL=%d p50=%dµs p90=%dµs p95=%dµs p99=%dµs qps=%.2f\n",
                summary.total, summary.ok, summary.fail, summary.p50, summary.p90, summary.p95, summary.p99,
                summary.qps);

        try {
            reporter.writeCsv(new java.io.File(out));
            System.out.println("CSV escrito en: " + out);
        } catch (Exception e) {
            System.err.println("No se pudo escribir CSV: " + e);
        }
    }

    private static String pickOp(String ops) {
        ops = Objects.requireNonNullElse(ops, "transfer").toLowerCase(Locale.ROOT);
        switch (ops) {
            case "transfer":
                return "transfer";
            case "loan":
                return "loan";
            case "mixed":
            default:
                return (System.nanoTime() & 1L) == 0L ? "transfer" : "loan";
        }
    }

    private static boolean has(String[] args, String... flags) {
        for (String a : args)
            for (String f : flags)
                if (a.equals(f))
                    return true;
        return false;
    }

    private static String opt(String[] args, String key, String def) {
        for (String a : args)
            if (a.startsWith(key + "="))
                return a.substring(key.length() + 1);
        return def;
    }

    private static void sleep(long ms) {
        try {
            if (ms > 0)
                Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static String trim(String s) {
        if (s == null)
            return "";
        s = s.trim();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
