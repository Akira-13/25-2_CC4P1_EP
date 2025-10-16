package cc4p1.worker;

import cc4p1.model.Account;
import cc4p1.model.Transaction;
import cc4p1.model.Loan;
import cc4p1.model.Payment;
import cc4p1.model.LoanUtils;
import cc4p1.storage.FileStorage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.stream.Collectors;


public final class WorkerServer {

    private final String nodeId;
    private final String host;
    private final int port;
    private final FileStorage storage;
    private HttpServer server;

    // Particionamiento/local store y tx-log
    private final Path txLogPath;
    private final ConcurrentHashMap<String, String> txIndex = new ConcurrentHashMap<>(); // txId -> payload OK

    // Concurrencia por cuenta
    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    // Cache RAM idempotencia HTTP (rápida)
    private final ConcurrentHashMap<String, String> txCache = new ConcurrentHashMap<>();

    // Métricas
    private final long startNanos = System.nanoTime();
    private final long maxPermits = Math.max(32, Runtime.getRuntime().availableProcessors() * 4);
    private final Semaphore inFlight = new Semaphore((int) maxPermits);
    private final ConcurrentLinkedQueue<Long> latSamplesMicros = new ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicLong reqTotal = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong txOk = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong txFail = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong readOk = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong readFail = new java.util.concurrent.atomic.AtomicLong();

    // Caos
    private volatile long chaosLatencyMs = 0L;
    private volatile boolean chaosDiskFail = false;

    public WorkerServer(String nodeId, String host, int port, Path nodeBase, int numParts) throws IOException {
        this.nodeId = nodeId;
        this.host = host;
        this.port = port;
        this.storage = FileStorage.open(nodeBase, numParts);
        this.txLogPath = nodeBase.resolve("tx.log");
        Files.createDirectories(nodeBase);
        if (!Files.exists(txLogPath)) Files.createFile(txLogPath);
        loadTxIndex();
        recoverFromTxLog();
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 128);
        server.createContext("/health", new HealthHandler());
        server.createContext("/healthz", new HealthHandler());
        server.createContext("/metrics", new MetricsHandler());

        server.createContext("/consultar_cuenta", new ConsultarCuentaHandler());
        server.createContext("/prestamo_estado", new PrestamoEstadoHandler());
        server.createContext("/estado_prestamo", new PrestamoEstadoHandler()); // alias

        server.createContext("/transferir", new TransferirHandler());
        server.createContext("/transferir_cuenta", new TransferirHandler()); 
        server.createContext("/consultar_transacciones", new ConsultarTransaccionesHandler());
        server.createContext("/crear_prestamo", new CrearPrestamoHandler());


        server.createContext("/chaos/latency", new ChaosLatencyHandler());
        server.createContext("/chaos/disk", new ChaosDiskHandler());
        server.createContext("/chaos/crash", new ChaosCrashHandler());

        server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));
        server.start();
        System.out.printf("[Worker %s] listening on %s:%d%n", nodeId, host, port);
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    // -------------- Internals: tx-log & recovery --------------

    private void loadTxIndex() {
        try (var lines = Files.lines(txLogPath, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                int p1 = line.indexOf('|'); if (p1 < 0) return;
                int p2 = line.indexOf('|', p1 + 1); if (p2 < 0) return;
                int p3 = line.indexOf('|', p2 + 1); if (p3 < 0) return;
                String txId = line.substring(p1 + 1, p2);
                String status = line.substring(p2 + 1, p3);
                String payload = line.substring(p3 + 1);
                if ("OK".equals(status)) {
                    txIndex.putIfAbsent(txId, payload);
                }
            });
        } catch (IOException ignore) { }
    }

    private synchronized void appendTxLog(String status, String txId, String payloadJson) {
        String ts = String.valueOf(System.currentTimeMillis());
        String line = ts + "|" + txId + "|" + status + "|" + payloadJson + System.lineSeparator();
        try {
            Files.writeString(txLogPath, line, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void recoverFromTxLog() {
        // Para S3: idempotencia y trazabilidad. No re-aplicamos efectos,
        // solo aseguramos que el índice de OK esté cargado.
        System.out.println("[Worker " + nodeId + "] idempotence index size=" + txIndex.size());
    }

    // -------------- Concurrencia --------------

    private ReentrantLock lockFor(long id) {
        return locks.computeIfAbsent(id, k -> new ReentrantLock());
    }

    // -------------- Handlers --------------

    final class HealthHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            long t0 = before(ex);
            if (t0 == -1L) return;
            try {
                sendJson(ex, 200, "{\"ok\":true}");
                readOk.incrementAndGet();
            } catch (Exception e) {
                readFail.incrementAndGet();
                sendJson(ex, 500, "{\"ok\":false}");
            } finally {
                after(t0);
            }
        }
    }

    final class MetricsHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            long t0 = before(ex);
            if (t0 == -1L) return;
            try {
                if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                    sendJson(ex, 405, "{\"ok\":false,\"error\":\"METHOD_NOT_ALLOWED\"}");
                    return;
                }
                long upSec = (System.nanoTime() - startNanos) / 1_000_000_000L;
                int inUse = (int) (maxPermits - inFlight.availablePermits());

                List<Long> samples = new ArrayList<>(latSamplesMicros);
                Collections.sort(samples);
                long p50 = percentile(samples, 50);
                long p95 = percentile(samples, 95);
                long p99 = percentile(samples, 99);

                String body = new StringBuilder(256)
                    .append("{\"ok\":true")
                    .append(",\"nodeId\":\"").append(jsonEscape(nodeId)).append('"')
                    .append(",\"host\":\"").append(jsonEscape(host)).append('"')
                    .append(",\"port\":").append(port)
                    .append(",\"txIndexSize\":").append(txIndex.size())
                    .append(",\"uptimeSec\":").append(upSec)
                    .append(",\"reqTotal\":").append(reqTotal.get())
                    .append(",\"inFlight\":").append(inUse)
                    .append(",\"qps\":").append(upSec > 0 ? (double) reqTotal.get() / (double) upSec : 0.0)
                    .append(",\"latMicros\":{\"p50\":").append(p50).append(",\"p95\":").append(p95).append(",\"p99\":").append(p99).append("}")
                    .append(",\"txOk\":").append(txOk.get())
                    .append(",\"txFail\":").append(txFail.get())
                    .append(",\"readOk\":").append(readOk.get())
                    .append(",\"readFail\":").append(readFail.get())
                    .append(",\"chaos\":{\"latencyMs\":").append(chaosLatencyMs).append(",\"diskFail\":").append(chaosDiskFail).append("}")
                    .append("}")
                    .toString();


                sendJson(ex, 200, body);
            } finally {
                after(t0);
            }
        }
    }

    final class ConsultarCuentaHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            long t0 = before(ex);
            if (t0 == -1L) return;
            try {
                if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                    sendJson(ex, 405, "{\"ok\":false,\"error\":\"METHOD_NOT_ALLOWED\"}");
                    readFail.incrementAndGet();
                    return;
                }
                if (chaosDiskFail) { sendJson(ex, 500, "{\"ok\":false,\"error\":\"DISK_FAULT\"}"); readFail.incrementAndGet(); return; }

                Map<String, String> q = parseQuery(ex.getRequestURI());
                String idStr = q.get("id");
                if (idStr == null) {
                    sendJson(ex, 400, "{\"ok\":false,\"error\":\"MISSING_ID\"}");
                    readFail.incrementAndGet();
                    return;
                }
                long id = Long.parseLong(idStr);
                var accOpt = storage.getCuenta(id);
                if (accOpt.isEmpty()) {
                    sendJson(ex, 404, "{\"ok\":false,\"error\":\"NOT_FOUND\"}");
                    readFail.incrementAndGet();
                    return;
                }

                Object a = accOpt.get();

                Number accId = asNumber(invokeAny(a, "id", "getId"));
                Object client = invokeAny(a,
                    "idCliente","getIdCliente", // fix: español
                    "clientId","getClientId","client","getClient","customerId","getCustomerId");
                Object balance = invokeAny(a, "balance", "getBalance", "saldo", "getSaldo", "amount", "getAmount");
                Object opened = invokeAny(a, "openedAt", "getOpenedAt", "apertura", "getApertura", "createdAt", "getCreatedAt");

                StringBuilder sb = new StringBuilder(128);
                sb.append("{\"ok\":true,\"account\":{");
                sb.append("\"id\":").append(accId != null ? accId.longValue() : id);
                if (client != null) sb.append(",\"cliente\":").append(jsonValue(client));
                if (balance != null) sb.append(",\"saldo\":").append(jsonValue(balance));
                if (opened != null) sb.append(",\"apertura\":").append(jsonValue(opened));
                sb.append("}}");

                sendJson(ex, 200, sb.toString());
                readOk.incrementAndGet();
            } catch (NumberFormatException nfe) {
                sendJson(ex, 400, "{\"ok\":false,\"error\":\"BAD_ID\"}");
                readFail.incrementAndGet();
            } catch (Exception e) {
                e.printStackTrace();
                sendJson(ex, 500, "{\"ok\":false,\"error\":\"INTERNAL\"}");
                readFail.incrementAndGet();
            } finally {
                after(t0);
            }
        }
    }

    final class TransferirHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            long t0 = before(ex);
            if (t0 == -1L) return;
            try {
                if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                    sendJson(ex, 405, "{\"ok\":false,\"error\":\"METHOD_NOT_ALLOWED\"}");
                    txFail.incrementAndGet();
                    return;
                }
                if (chaosDiskFail) {
                    sendJson(ex, 500, "{\"ok\":false,\"error\":\"DISK_FAULT\"}");
                    txFail.incrementAndGet();
                    return;
                }

                Map<String, String> q = parseQuery(ex.getRequestURI());
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, String> m = body.isBlank() ? new HashMap<>() : parseJsonMap(body);

                long from   = parseLongOr(m.get("from"),   parseLongOr(q.get("origen"),  -1));
                long to     = parseLongOr(m.get("to"),     parseLongOr(q.get("destino"), -1));
                String txId = (m.containsKey("txId") ? m.get("txId") : q.get("txId"));
                String montoStr = (m.containsKey("monto") ? m.get("monto") : q.get("monto"));

                if (from <= 0 || to <= 0 || from == to || txId == null || montoStr == null) {
                    sendJson(ex, 400, "{\"ok\":false,\"error\":\"BAD_REQUEST\"}");
                    txFail.incrementAndGet();
                    return;
                }

                var priorRam = txCache.get(txId);
                if (priorRam != null) {
                    sendJson(ex, 200, priorRam);
                    txOk.incrementAndGet();
                    return;
                }

                BigDecimal monto;
                try { monto = new BigDecimal(montoStr); }
                catch (Exception bad) {
                    sendJson(ex, 400, "{\"ok\":false,\"error\":\"BAD_AMOUNT\"}");
                    txFail.incrementAndGet();
                    return;
                }

                long a = Math.min(from, to), b = Math.max(from, to);
                var la = lockFor(a);
                var lb = lockFor(b);
                la.lock(); lb.lock();
                try {
                    String okPrev = txIndex.get(txId);
                    if (okPrev != null) {
                        cacheAndReply(ex, txId, 200, okPrev);
                        txOk.incrementAndGet();
                        return;
                    }
                    var txMaybe = storage.getTransaccionById(txId);
                    if (txMaybe.isPresent()) {
                        String ok = "{\"ok\":true,\"txId\":\"" + jsonEscape(txId) + "\",\"monto\":\"" + jsonEscape(monto.toString()) + "\"}";
                        cacheAndReply(ex, txId, 200, ok);
                        txOk.incrementAndGet();
                        return;
                    }

                    var accFrom = storage.getCuenta(from).orElse(null);
                    var accTo   = storage.getCuenta(to).orElse(null);
                    if (accFrom == null || accTo == null) {
                        cacheAndReply(ex, txId, 404, "{\"ok\":false,\"error\":\"ACCOUNT_NOT_FOUND\"}");
                        txFail.incrementAndGet();
                        return;
                    }
                    var saldoFrom = balanceOf(accFrom);
                    if (saldoFrom.compareTo(monto) < 0) {
                        sendJson(ex, 409, "{\"ok\":false,\"error\":\"INSUFFICIENT_FUNDS\"}");
                        txFail.incrementAndGet();
                        return;
                    }

                    appendTxLog("BEGIN", txId,
                            "{\"from\":" + from + ",\"to\":" + to + ",\"monto\":\"" + jsonEscape(monto.toString()) + "\"}");

                    var accFromNew = withSaldo(accFrom, saldoFrom.subtract(monto));
                    var accToNew   = withSaldo(accTo,   balanceOf(accTo).add(monto));

                    try {
                        storage.putCuenta((Account) accFromNew);
                        storage.putCuenta((Account) accToNew);

                        // CSV: id_tx;id_cuenta;tipo;monto;fecha
                        // Registramos dos asientos con el MISMO txId
                        storage.appendTransaccion(Transaction.debito(txId, from, monto));  // tipo=DEBITO
                        storage.appendTransaccion(Transaction.credito(txId, to,   monto));  // tipo=CREDITO
                    } catch (RuntimeException w) {
                        appendTxLog("FAIL", txId, "{\"error\":\"WRITE_FAILED\"}");
                        sendJson(ex, 500, "{\"ok\":false,\"error\":\"INTERNAL_WRITE_FAIL\"}");
                        txFail.incrementAndGet();
                        return;
                    }

                    String ok = "{\"ok\":true,\"txId\":\"" + jsonEscape(txId) + "\",\"monto\":\"" + jsonEscape(monto.toString()) + "\"}";
                    appendTxLog("OK", txId, ok);
                    txIndex.putIfAbsent(txId, ok);
                    cacheAndReply(ex, txId, 200, ok);
                    txOk.incrementAndGet();
                } finally {
                    lb.unlock(); la.unlock();
                }
            } finally {
                after(t0);
            }
        }
    }
    
    


    final class PrestamoEstadoHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            long t0 = before(ex);
            if (t0 == -1L) return;
            try {
                if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                    sendJson(ex, 405, "{\"ok\":false,\"error\":\"METHOD_NOT_ALLOWED\"}");
                    readFail.incrementAndGet();
                    return;
                }
                if (chaosDiskFail) { sendJson(ex, 500, "{\"ok\":false,\"error\":\"DISK_FAULT\"}"); readFail.incrementAndGet(); return; }

                var q = parseQuery(ex.getRequestURI());
                String idStr = q.get("id");
                if (idStr == null) {
                    sendJson(ex, 400, "{\"ok\":false,\"error\":\"MISSING_ID\"}");
                    readFail.incrementAndGet();
                    return;
                }
                long idCuenta = Long.parseLong(idStr);

                var accOpt = storage.getCuenta(idCuenta);
                if (accOpt.isEmpty()) { sendJson(ex, 404, "{\"ok\":false,\"error\":\"NOT_FOUND\"}"); readFail.incrementAndGet(); return; }
                Object acc = accOpt.get();
                long idCliente = clientIdOf(acc);

                var loans = storage.getPrestamosByCliente(idCliente).collect(Collectors.toList());
                StringBuilder sb = new StringBuilder(256);
                sb.append("{\"ok\":true,\"cuenta\":").append(idCuenta)
                  .append(",\"cliente\":").append(idCliente)
                  .append(",\"prestamos\":[");
                boolean first = true;
                for (Loan loan : loans) {   // ✅ ahora es List<Loan>
                    var pagos = storage.getPagosByPrestamo(loan.idPrestamo()); // suele ser List<Payment>
                    var calc  = LoanUtils.withPendienteActualizado(loan, pagos);
                    if (!first) sb.append(',');
                    first = false;
                    sb.append("{\"idPrestamo\":").append(loan.idPrestamo())
                      .append(",\"monto\":\"").append(loan.monto()).append('"')
                      .append(",\"pendiente\":\"").append(calc.pendiente()).append('"')
                      .append(",\"estado\":\"").append(calc.estado()).append('"')
                      .append("}");
                }
                sb.append("]}");
                sendJson(ex, 200, sb.toString());
                readOk.incrementAndGet();

            } catch (NumberFormatException e) {
                sendJson(ex, 400, "{\"ok\":false,\"error\":\"BAD_ID\"}");
                readFail.incrementAndGet();
            } catch (Exception e) {
                e.printStackTrace();
                sendJson(ex, 500, "{\"ok\":false,\"error\":\"INTERNAL\"}");
                readFail.incrementAndGet();
            } finally {
                after(t0);
            }
        }
    }
    
    final class ConsultarTransaccionesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            long t0 = before(ex);
            try {
                if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                    sendJson(ex, 405, "{\"ok\":false,\"error\":\"METHOD_NOT_ALLOWED\"}");
                    readFail.incrementAndGet();
                    return;
                }
                if (chaosDiskFail) {
                    sendJson(ex, 500, "{\"ok\":false,\"error\":\"DISK_FAULT\"}");
                    readFail.incrementAndGet();
                    return;
                }

                Map<String, String> q = parseQuery(ex.getRequestURI());
                String idStr = q.get("id");
                if (idStr == null) {
                    sendJson(ex, 400, "{\"ok\":false,\"error\":\"VALIDACION\",\"msg\":\"Falta parámetro id\"}");
                    readFail.incrementAndGet();
                    return;
                }

                long cuentaId;
                try { cuentaId = Long.parseLong(idStr); }
                catch (NumberFormatException e) {
                    sendJson(ex, 400, "{\"ok\":false,\"error\":\"VALIDACION\",\"msg\":\"Parámetro id inválido\"}");
                    readFail.incrementAndGet();
                    return;
                }

                var accOpt = storage.getCuenta(cuentaId);
                if (accOpt.isEmpty()) {
                    sendJson(ex, 404, "{\"ok\":false,\"error\":\"NOT_FOUND\",\"msg\":\"Cuenta inexistente\"}");
                    readFail.incrementAndGet();
                    return;
                }

                // FileStorage.getTransaccionesByCuenta → Stream<Transaction>
                List<Transaction> txs;
                try {
                    var stream = storage.getTransaccionesByCuenta(cuentaId);
                    txs = (stream == null) ? List.of() : stream.collect(Collectors.toList());
                } catch (Throwable t) {
                    txs = List.of();
                }

                StringBuilder sb = new StringBuilder(256);
                sb.append("{\"ok\":true,\"cuenta\":").append(cuentaId).append(",\"transacciones\":[");
                boolean first = true;
                for (Transaction tx : txs) {
                    if (!first) sb.append(',');
                    first = false;

                    String txId  = String.valueOf(invokeAny(tx, "idTx", "getIdTx", "txId", "getTxId", "id", "getId"));
                    Object tipo  = invokeAny(tx, "tipo", "getTipo", "type", "getType");          // "DEBITO"/"CREDITO"
                    Object monto = invokeAny(tx, "monto", "getMonto", "amount", "getAmount");
                    Object fecha = invokeAny(tx, "fecha", "getFecha", "date", "getDate");        // String/LocalDate

                    sb.append('{')
                      .append("\"txId\":").append(jsonValue(txId))
                      .append(",\"tipo\":").append(jsonValue(String.valueOf(tipo)))
                      .append(",\"monto\":").append(jsonValue(String.valueOf(monto)))
                      .append(",\"fecha\":").append(jsonValue(String.valueOf(fecha)))
                      .append('}');
                }
                sb.append("]}");

                sendJson(ex, 200, sb.toString());
                readOk.incrementAndGet();
            } catch (Exception e) {
                e.printStackTrace();
                sendJson(ex, 500, "{\"ok\":false,\"error\":\"INTERNAL\"}");
                readFail.incrementAndGet();
            } finally {
                if (t0 != -1L) after(t0);
            }
        }
    }

    
    final class CrearPrestamoHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange ex) throws IOException {
        long t0 = before(ex);
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 405, "{\"ok\":false,\"error\":\"METHOD_NOT_ALLOWED\"}");
                txFail.incrementAndGet();
                return;
            }
            if (chaosDiskFail) {
                sendJson(ex, 500, "{\"ok\":false,\"error\":\"DISK_FAULT\"}");
                txFail.incrementAndGet();
                return;
            }

            Map<String, String> q = parseQuery(ex.getRequestURI());
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> m = body.isBlank() ? new HashMap<>() : parseJsonMap(body);

            long cuentaId = parseLongOr(m.get("cuentaId"), parseLongOr(q.get("cuentaId"), -1));
            String montoStr = m.containsKey("monto") ? m.get("monto") : q.get("monto");
            String tasaStr  = m.containsKey("tasa")  ? m.get("tasa")  : q.get("tasa");
            String loanIdStr = m.containsKey("loanId") ? m.get("loanId") : q.get("loanId"); // opcional (idempotencia)

            if (cuentaId <= 0 || montoStr == null) {
                sendJson(ex, 400, "{\"ok\":false,\"error\":\"VALIDACION\",\"msg\":\"Faltan cuentaId o monto\"}");
                txFail.incrementAndGet();
                return;
            }
            BigDecimal monto;
            try { monto = new BigDecimal(montoStr); }
            catch (Exception e) {
                sendJson(ex, 400, "{\"ok\":false,\"error\":\"VALIDACION\",\"msg\":\"monto inválido\"}");
                txFail.incrementAndGet();
                return;
            }
            BigDecimal tasa = BigDecimal.ZERO;
            if (tasaStr != null) {
                try { tasa = new BigDecimal(tasaStr); } catch (Exception ignore) {}
            }

            // Verifica cuenta y extrae cliente
            var accOpt = storage.getCuenta(cuentaId);
            if (accOpt.isEmpty()) {
                sendJson(ex, 404, "{\"ok\":false,\"error\":\"NOT_FOUND\",\"msg\":\"Cuenta inexistente\"}");
                txFail.incrementAndGet();
                return;
            }
            long idCliente = clientIdOf(accOpt.get());

            // Determina loanId (si no viene, usa timestamp)
            long loanId = (loanIdStr != null && !loanIdStr.isBlank())
                    ? parseLongOr(loanIdStr, System.currentTimeMillis())
                    : System.currentTimeMillis();

            // Idempotencia por loanId SIN getPrestamoById: busca el mismo id en préstamos del cliente
            try {
                var existing = storage.getPrestamosByCliente(idCliente)
                    .filter(l -> {
                        Object v = invokeAny(l, "idPrestamo", "getIdPrestamo", "id", "getId");
                        if (v == null) return false;
                        try { return Long.parseLong(String.valueOf(v)) == loanId; }
                        catch (Exception e2) { return false; }
                    })
                    .findFirst();

                if (existing.isPresent()) {
                    // Devuelve lo mismo que si lo crearas ahora (sin duplicar)
                    Object exLoan = existing.get();
                    Object pendiente = invokeAny(exLoan, "pendiente", "getPendiente");
                    String resp = new StringBuilder(160)
                        .append("{\"ok\":true,\"msg\":\"ALREADY_EXISTS\",\"loanId\":").append(loanId)
                        .append(",\"cliente\":").append(idCliente)
                        .append(",\"monto\":\"").append(jsonEscape(monto.toString())).append('"')
                        .append(",\"tasa\":\"").append(jsonEscape(tasa.toString())).append('"')
                        .append(",\"pendiente\":").append(pendiente == null ? "\""+jsonEscape(monto.toString())+"\"" : jsonValue(pendiente))
                        .append("}")
                        .toString();
                    sendJson(ex, 200, resp);
                    txOk.incrementAndGet();
                    return;
                }
            } catch (Throwable ignore) {
                // si el storage aún no implementa préstamos por cliente, seguimos
            }

            // Construye el préstamo usando Loan.newLoan(...) si existe (como en LoanDemo)
            Loan loan;
            try {
                try {
                    var newLoan = Loan.class.getDeclaredMethod("newLoan",
                            long.class, long.class, BigDecimal.class, BigDecimal.class, LocalDate.class);
                    loan = (Loan) newLoan.invoke(null, loanId, idCliente, monto, tasa, LocalDate.now());
                } catch (NoSuchMethodException nf) {
                    // Fallback a constructores comunes
                    try {
                        var ctor = Loan.class.getDeclaredConstructor(long.class, long.class, BigDecimal.class, BigDecimal.class, LocalDate.class);
                        loan = ctor.newInstance(loanId, idCliente, monto, tasa, LocalDate.now());
                    } catch (NoSuchMethodException nf2) {
                        var ctor = Loan.class.getDeclaredConstructor(long.class, long.class, BigDecimal.class, LocalDate.class);
                        loan = ctor.newInstance(loanId, idCliente, monto, LocalDate.now());
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendJson(ex, 500, "{\"ok\":false,\"error\":\"INTERNAL\",\"msg\":\"No se pudo instanciar Loan\"}");
                txFail.incrementAndGet();
                return;
            }

            // Guarda préstamo (P1 debe tener putPrestamo)
            try {
                storage.putPrestamo(loan);
            } catch (RuntimeException re) {
                sendJson(ex, 500, "{\"ok\":false,\"error\":\"INTERNAL_WRITE_FAIL\"}");
                txFail.incrementAndGet();
                return;
            }

            Object pendiente = invokeAny(loan, "pendiente", "getPendiente");
            String ok = new StringBuilder(160)
                .append("{\"ok\":true,\"loanId\":").append(loanId)
                .append(",\"cliente\":").append(idCliente)
                .append(",\"monto\":\"").append(jsonEscape(monto.toString())).append('"')
                .append(",\"tasa\":\"").append(jsonEscape(tasa.toString())).append('"')
                .append(",\"pendiente\":").append(pendiente == null ? "\""+jsonEscape(monto.toString())+"\"" : jsonValue(pendiente))
                .append("}")
                .toString();

            sendJson(ex, 200, ok);
            txOk.incrementAndGet();

            } catch (Exception e) {
                e.printStackTrace();
                sendJson(ex, 500, "{\"ok\":false,\"error\":\"INTERNAL\"}");
                txFail.incrementAndGet();
            } finally {
                if (t0 != -1L) after(t0);
            }
        }
    }



    // -------------- Caos Handlers --------------

    final class ChaosLatencyHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            long t0 = before(ex);
            if (t0 == -1L) return;
            try {
                var q = parseQuery(ex.getRequestURI());
                String msStr = q.get("ms");
                long ms = 0L;
                try { if (msStr != null) ms = Long.parseLong(msStr); } catch (Exception ignore) {}
                chaosLatencyMs = Math.max(0L, ms);
                sendJson(ex, 200, "{\"ok\":true,\"latencyMs\":" + chaosLatencyMs + "}");
            } finally {
                after(t0);
            }
        }
    }

    final class ChaosDiskHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            long t0 = before(ex);
            if (t0 == -1L) return;
            try {
                var q = parseQuery(ex.getRequestURI());
                String on = q.getOrDefault("on", "false");
                chaosDiskFail = "true".equalsIgnoreCase(on);
                sendJson(ex, 200, "{\"ok\":true,\"diskFail\":" + chaosDiskFail + "}");
            } finally {
                after(t0);
            }
        }
    }

    final class ChaosCrashHandler implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            // responder 200 y luego terminar el proceso
            sendJson(ex, 200, "{\"ok\":true,\"crash\":true}");
            new Thread(() -> {
                try { Thread.sleep(100); } catch (InterruptedException ignore) {}
                System.err.println("[CHAOS] Simulando crash del proceso…");
                System.exit(1);
            }).start();
        }
    }

    // -------------- Métricas helpers --------------

    private long before(HttpExchange ex) {
        reqTotal.incrementAndGet();
        if (!inFlight.tryAcquire()) {
            try { sendJson(ex, 503, "{\"ok\":false,\"error\":\"BUSY\"}"); } catch (IOException ignore) {}
            return -1L; // <-- NO lances excepción
        }
        if (chaosLatencyMs > 0) { try { Thread.sleep(chaosLatencyMs); } catch (InterruptedException ignore) {} }
        return System.nanoTime();
    }


    private void after(long t0) {
        long micros = (System.nanoTime() - t0) / 1_000;
        if (latSamplesMicros.size() < 5000) latSamplesMicros.add(micros);
        inFlight.release();
    }

    private static long percentile(List<Long> xs, int pct) {
        if (xs.isEmpty()) return 0;
        int idx = Math.min(xs.size() - 1, Math.max(0, (int) Math.round((pct / 100.0) * (xs.size() - 1))));
        return xs.get(idx);
    }

    // -------------- Utils --------------

    private void cacheAndReply(HttpExchange ex, String txId, int code, String body) throws IOException {
        if (txId != null && code == 200) txCache.putIfAbsent(txId, body);
        sendJson(ex, code, body);
    }

    private static long parseLongOr(String s, long def) {
        if (s == null) return def;
        try { return Long.parseLong(s); } catch (Exception e) { return def; }
    }

    private static String buildOkJson(String txId, long from, long to, BigDecimal monto) {
        return "{\"ok\":true,\"txId\":\"" + jsonEscape(txId) + "\",\"from\":" + from
            + ",\"to\":" + to + ",\"monto\":\"" + jsonEscape(monto.toString()) + "\"}";
    }

    static Object invokeAny(Object target, String... methodNames) {
        for (String m : methodNames) {
            try {
                var mm = target.getClass().getMethod(m);
                return mm.invoke(target);
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return null;
    }

    static Number asNumber(Object o) {
        if (o instanceof Number n) return n;
        if (o instanceof String s) {
            try { return Long.parseLong(s); } catch (Exception ignore) {}
            try { return Double.parseDouble(s); } catch (Exception ignore) {}
        }
        return null;
    }

    static BigDecimal balanceOf(Object a) {
        Object v = invokeAny(a, "balance", "getBalance", "saldo", "getSaldo", "amount", "getAmount");
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        if (v != null) return new BigDecimal(v.toString());
        return BigDecimal.ZERO;
    }

    static long accountIdOf(Object a) {
        Number n = asNumber(invokeAny(a, "id", "getId"));
        return (n != null) ? n.longValue() : -1L;
    }

    static long clientIdOf(Object a) {
        Number n = asNumber(invokeAny(a,
            "idCliente","getIdCliente", // español
            "clientId","getClientId","client","getClient","customerId","getCustomerId"));
        return (n != null) ? n.longValue() : -1L;
    }

    static LocalDate openedAtOf(Object a) {
        Object v = invokeAny(a, "openedAt", "getOpenedAt", "apertura", "getApertura", "createdAt", "getCreatedAt");
        if (v instanceof LocalDate d) return d;
        if (v != null) return LocalDate.parse(v.toString());
        return LocalDate.now();
    }

    static Object withSaldo(Object oldAcc, BigDecimal nuevoSaldo) {
        try {
            long id = accountIdOf(oldAcc);
            long cli = clientIdOf(oldAcc);
            LocalDate open = openedAtOf(oldAcc);
            try {
                var ctor = oldAcc.getClass().getConstructor(long.class, long.class, BigDecimal.class, LocalDate.class);
                return ctor.newInstance(id, cli, nuevoSaldo, open);
            } catch (NoSuchMethodException ns) {
                var ctor = oldAcc.getClass().getConstructor(long.class, long.class, BigDecimal.class);
                return ctor.newInstance(id, cli, nuevoSaldo);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    static String jsonValue(Object v) {
        if (v == null) return "null";
        if (v instanceof Number n) return (n instanceof BigDecimal) ? "\"" + n.toString() + "\"" : n.toString();
        if (v instanceof Boolean b) return b.toString();
        return "\"" + jsonEscape(String.valueOf(v)) + "\"";
    }

    static Map<String, String> parseJsonMap(String s) {
        Map<String, String> m = new HashMap<>();
        s = s.trim();
        if (s.startsWith("{") && s.endsWith("}")) s = s.substring(1, s.length() - 1);
        for (String part : s.split(",")) {
            int i = part.indexOf(':');
            if (i < 0) continue;
            String k = part.substring(0, i).trim().replaceAll("^\"|\"$", "");
            String v = part.substring(i + 1).trim();
            v = v.replaceAll("^\"|\"$", "");
            m.put(k, v);
        }
        return m;
    }

    static Map<String, String> parseQuery(URI uri) {
        Map<String, String> map = new HashMap<>();
        String q = uri.getRawQuery();
        if (q == null) return map;
        for (String pair : q.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                map.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    static void sendJson(HttpExchange ex, int code, String body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}


