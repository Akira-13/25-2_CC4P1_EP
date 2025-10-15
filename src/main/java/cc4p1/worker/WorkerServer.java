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
import java.util.concurrent.locks.ReentrantLock;
import java.math.BigDecimal;
import java.time.LocalDate;

public final class WorkerServer {

    private final String nodeId;
    private final String host;
    private final int port;
    private final FileStorage storage;
    private HttpServer server;

    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> txCache = new ConcurrentHashMap<>();

    // --- Tx-log (idempotencia persistente) ---
    private final Path txLogPath;
    private final ConcurrentHashMap<String, String> txIndex = new ConcurrentHashMap<>(); // txId -> respuesta JSON OK

    public WorkerServer(String nodeId, String host, int port, Path nodeBase, int numParts) throws IOException {
        this.nodeId = nodeId;
        this.host = host;
        this.port = port;
        this.storage = FileStorage.open(nodeBase, numParts);
        this.txLogPath = nodeBase.resolve("tx.log");
        Files.createDirectories(nodeBase);
        if (!Files.exists(txLogPath)) Files.createFile(txLogPath);
        loadTxIndex();
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 128);
        server.createContext("/health", new HealthHandler());
        server.createContext("/healthz", new HealthHandler());
        server.createContext("/consultar_cuenta", new ConsultarCuentaHandler());

        // Transferencia: soporta JSON (body) y querystring (alias para coordinador)
        server.createContext("/transferir", new TransferirHandler());
        server.createContext("/transferir_cuenta", new TransferirHandler()); // alias

        // Préstamos: alias compatibles con coordinador
        server.createContext("/prestamo_estado", new PrestamoEstadoHandler());
        server.createContext("/estado_prestamo", new PrestamoEstadoHandler()); // alias

        server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));
        server.start();
        System.out.printf("[Worker %s] listening on %s:%d%n", nodeId, host, port);
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    // --- Lock helpers ---
    private ReentrantLock lockFor(long id) {
        return locks.computeIfAbsent(id, k -> new ReentrantLock());
    }

    // --- Tx-log helpers ---
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
        } catch (IOException ignore) {}
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

    private String priorOkResponse(String txId) {
        return txIndex.get(txId);
    }

    // --- Handlers ---

    final class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            sendJson(ex, 200, "{\"ok\":true}");
        }
    }

    final class ConsultarCuentaHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 405, "{\"ok\":false,\"error\":\"METHOD_NOT_ALLOWED\"}");
                return;
            }
            Map<String, String> q = parseQuery(ex.getRequestURI());
            String idStr = q.get("id");
            if (idStr == null) {
                sendJson(ex, 400, "{\"ok\":false,\"error\":\"MISSING_ID\"}");
                return;
            }
            try {
                long id = Long.parseLong(idStr);
                var accOpt = storage.getCuenta(id);
                if (accOpt.isEmpty()) {
                    sendJson(ex, 404, "{\"ok\":false,\"error\":\"NOT_FOUND\"}");
                    return;
                }

                Object a = accOpt.get();

                Number accId = asNumber(invokeAny(a, "id", "getId"));
                Object client = invokeAny(a, "clientId", "getClientId", "client", "getClient", "customerId", "getCustomerId");
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
            } catch (NumberFormatException nfe) {
                sendJson(ex, 400, "{\"ok\":false,\"error\":\"BAD_ID\"}");
            } catch (Exception e) {
                e.printStackTrace();
                sendJson(ex, 500, "{\"ok\":false,\"error\":\"INTERNAL\"}");
            }
        }
    }

    final class TransferirHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 405, "{\"ok\":false,\"error\":\"METHOD_NOT_ALLOWED\"}");
                return;
            }

            // Acepta JSON y/o querystring (alias con el coordinador)
            var q = parseQuery(ex.getRequestURI());
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> m = body.isBlank() ? new HashMap<>() : parseJsonMap(body);

            long from = parseLongOr(m.get("from"), parseLongOr(q.get("origen"), -1));
            long to   = parseLongOr(m.get("to"),   parseLongOr(q.get("destino"), -1));
            String txId = (m.containsKey("txId") ? m.get("txId") : q.get("txId"));
            String montoStr = (m.containsKey("monto") ? m.get("monto") : q.get("monto"));

            if (from <= 0 || to <= 0 || from == to || txId == null || montoStr == null) {
                sendJson(ex, 400, "{\"ok\":false,\"error\":\"BAD_REQUEST\"}");
                return;
            }

            // Idempotencia en RAM rápida
            var priorRam = txCache.get(txId);
            if (priorRam != null) {
                sendJson(ex, 200, priorRam);
                return;
            }

            BigDecimal monto;
            try { monto = new BigDecimal(montoStr); }
            catch (Exception bad) { sendJson(ex, 400, "{\"ok\":false,\"error\":\"BAD_AMOUNT\"}"); return; }

            long a = Math.min(from, to), b = Math.max(from, to);
            var la = lockFor(a);
            var lb = lockFor(b);
            la.lock();
            lb.lock();
            try {
                // Idempotencia persistente (replay tras reinicio)
                var okPrevio = priorOkResponse(txId);
                if (okPrevio != null) {
                    cacheAndReply(ex, txId, 200, okPrevio);
                    return;
                }

                // Si P1 ya registró la transacción (por ejemplo, por otro worker), respóndela igual
                var txMaybe = storage.getTransaccionById(txId);
                if (txMaybe.isPresent()) {
                    String ok = buildOkJson(txId, from, to, monto);
                    cacheAndReply(ex, txId, 200, ok);
                    return;
                }

                // Cargar cuentas y validar
                var accFrom = storage.getCuenta(from).orElse(null);
                var accTo   = storage.getCuenta(to).orElse(null);
                if (accFrom == null || accTo == null) {
                    cacheAndReply(ex, txId, 404, "{\"ok\":false,\"error\":\"ACCOUNT_NOT_FOUND\"}");
                    return;
                }
                var saldoFrom = balanceOf(accFrom);
                if (saldoFrom.compareTo(monto) < 0) {
                    sendJson(ex, 409, "{\"ok\":false,\"error\":\"INSUFFICIENT_FUNDS\"}");
                    return;
                }

                // BEGIN (opcional para trazabilidad simple)
                appendTxLog("BEGIN", txId, "{\"from\":" + from + ",\"to\":" + to + ",\"monto\":\"" + jsonEscape(monto.toString()) + "\"}");

                // Aplicar saldos y persistir
                var accFromNew = withSaldo(accFrom, saldoFrom.subtract(monto));
                var accToNew   = withSaldo(accTo,   balanceOf(accTo).add(monto));

                try {
                    storage.putCuenta((Account) accFromNew);
                    storage.putCuenta((Account) accToNew);

                    // Registrar la transacción en el log de P1 (idempotente por txId)
                    // Aquí usamos un único asiento; si tu modelo requiere débito y crédito por separado, crea 2 transacciones con ids distintos.
                    Transaction tx = Transaction.debito(txId, from, monto); // o tu factory equivalente
                    storage.appendTransaccion(tx);
                } catch (RuntimeException w) {
                    appendTxLog("FAIL", txId, "{\"error\":\"WRITE_FAILED\"}");
                    sendJson(ex, 500, "{\"ok\":false,\"error\":\"INTERNAL_WRITE_FAIL\"}");
                    return;
                }

                String ok = buildOkJson(txId, from, to, monto);
                appendTxLog("OK", txId, ok);
                txIndex.putIfAbsent(txId, ok);
                cacheAndReply(ex, txId, 200, ok);

            } finally {
                lb.unlock();
                la.unlock();
            }
        }
    }

    final class PrestamoEstadoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 405, "{\"ok\":false,\"error\":\"METHOD_NOT_ALLOWED\"}");
                return;
            }
            var q = parseQuery(ex.getRequestURI());
            String idStr = q.get("id");
            if (idStr == null) {
                sendJson(ex, 400, "{\"ok\":false,\"error\":\"MISSING_ID\"}");
                return;
            }
            try {
                long idCuenta = Long.parseLong(idStr);

                var accOpt = storage.getCuenta(idCuenta);
                if (accOpt.isEmpty()) { sendJson(ex, 404, "{\"ok\":false,\"error\":\"NOT_FOUND\"}"); return; }
                Object acc = accOpt.get();
                long idCliente = clientIdOf(acc);

                var loans = storage.getPrestamosByCliente(idCliente).toList();
                StringBuilder sb = new StringBuilder(256);
                sb.append("{\"ok\":true,\"cuenta\":").append(idCuenta)
                  .append(",\"cliente\":").append(idCliente)
                  .append(",\"prestamos\":[");
                boolean first = true;
                for (Loan loan : loans) {
                    var pagos = storage.getPagosByPrestamo(loan.idPrestamo());
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

            } catch (NumberFormatException e) {
                sendJson(ex, 400, "{\"ok\":false,\"error\":\"BAD_ID\"}");
            } catch (Exception e) {
                e.printStackTrace();
                sendJson(ex, 500, "{\"ok\":false,\"error\":\"INTERNAL\"}");
            }
        }
    }

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

    // --- JSON and util helpers ---

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
            "idCliente", "getIdCliente",   
            "clientId", "getClientId",
            "client", "getClient",
            "customerId", "getCustomerId"
        ));
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
            var ctor = oldAcc.getClass().getConstructor(long.class, long.class, BigDecimal.class, LocalDate.class);
            return ctor.newInstance(id, cli, nuevoSaldo, open);
        } catch (NoSuchMethodException e) {
            try {
                long id = accountIdOf(oldAcc);
                long cli = clientIdOf(oldAcc);
                var ctor = oldAcc.getClass().getConstructor(long.class, long.class, BigDecimal.class);
                return ctor.newInstance(id, cli, nuevoSaldo);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
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
        if (v instanceof Number n)
            return (n instanceof BigDecimal) ? "\"" + n.toString() + "\"" : n.toString();
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
