package cc4p1.clients.cli;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Ejecuta una campaña de benchmark en 3 fases: normal → fallo → recuperación.
 * Puede activar fallos en el worker vía endpoints de caos (/chaos/disk,
 * /chaos/latency).
 */
public final class BenchCampaign {
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || has(args, "-h", "--help")) {
            System.out.println(
                    "Uso: bench-campaign --coordinator=host:port [--worker=http://host:port] --threads=N --ops=transfer|loan|mixed --min=MIN --max=MAX --idClienteRange=a:b --accountRange=a:b --rate=Rps --tasaAnual=VAL --normalSec=S1 --failSec=S2 --recoverSec=S3 --failMode=disk|latency|none --latencyMs=MS --outPrefix=bench");
            return;
        }

        String coord = opt(args, "--coordinator", "localhost:8080");
        String worker = opt(args, "--worker", ""); // http://host:port
        int threads = Integer.parseInt(opt(args, "--threads", "4"));
        String ops = opt(args, "--ops", "loan");
        double min = Double.parseDouble(opt(args, "--min", "10"));
        double max = Double.parseDouble(opt(args, "--max", "100"));
        String idcRange = opt(args, "--idClienteRange", "0:0");
        String accRange = opt(args, "--accountRange", "0:0");
        double rate = Double.parseDouble(opt(args, "--rate", "0"));
        String tasaAnual = opt(args, "--tasaAnual", "0.25");
        int s1 = Integer.parseInt(opt(args, "--normalSec", "20"));
        int s2 = Integer.parseInt(opt(args, "--failSec", "20"));
        int s3 = Integer.parseInt(opt(args, "--recoverSec", "20"));
        String failMode = opt(args, "--failMode", "none");
        long latencyMs = Long.parseLong(opt(args, "--latencyMs", "200"));
        String outPrefix = opt(args, "--outPrefix", "bench");

        // Fase 1: normal
        chaosReset(worker);
        runLoadGen(coord, threads, s1, ops, min, max, idcRange, accRange, rate, tasaAnual, outPrefix + "_normal.csv",
                outPrefix + "_normal.json");

        // Fase 2: fallo
        if (!worker.isBlank()) {
            switch (failMode.toLowerCase()) {
                case "disk":
                    chaosDisk(worker, true);
                    break;
                case "latency":
                    chaosLatency(worker, latencyMs);
                    break;
                default:
                    /* none */ break;
            }
        }
        runLoadGen(coord, threads, s2, ops, min, max, idcRange, accRange, rate, tasaAnual, outPrefix + "_fail.csv",
                outPrefix + "_fail.json");

        // Fase 3: recuperación
        chaosReset(worker);
        runLoadGen(coord, threads, s3, ops, min, max, idcRange, accRange, rate, tasaAnual, outPrefix + "_recover.csv",
                outPrefix + "_recover.json");

        System.out.println("Campaña completada. Archivos: " + outPrefix + "_{normal,fail,recover}.{csv,json}");
    }

    static void runLoadGen(String coord, int threads, int secs, String ops, double min, double max,
            String idcRange, String accRange, double rate, String tasaAnual, String outCsv, String outJson)
            throws Exception {
        java.util.List<String> a = new java.util.ArrayList<>();
        a.add("--coordinator=" + coord);
        a.add("--threads=" + threads);
        a.add("--durationSec=" + secs);
        a.add("--ops=" + ops);
        a.add("--min=" + min);
        a.add("--max=" + max);
        if (!idcRange.equals("0:0"))
            a.add("--idClienteRange=" + idcRange);
        if (!accRange.equals("0:0"))
            a.add("--accountRange=" + accRange);
        if (rate > 0)
            a.add("--rate=" + rate);
        if (ops.toLowerCase().contains("loan"))
            a.add("--tasaAnual=" + tasaAnual);
        a.add("--out=" + outCsv);
        a.add("--summaryJson=" + outJson);
        LoadGenerator.main(a.toArray(new String[0]));
    }

    static void chaosReset(String worker) {
        if (worker.isBlank())
            return;
        chaosLatency(worker, 0);
        chaosDisk(worker, false);
    }

    static void chaosLatency(String worker, long ms) {
        if (worker.isBlank())
            return;
        httpGet(worker + "/chaos/latency?ms=" + ms);
    }

    static void chaosDisk(String worker, boolean on) {
        if (worker.isBlank())
            return;
        httpGet(worker + "/chaos/disk?on=" + on);
    }

    static void httpGet(String url) {
        try {
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest r = HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(3)).build();
            c.send(r, HttpResponse.BodyHandlers.ofString());
        } catch (Exception ignore) {
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
}
