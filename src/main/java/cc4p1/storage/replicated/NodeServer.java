package cc4p1.storage.replicated;

import cc4p1.model.Account;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * Servidor mínimo que expone consultas locales leyendo los CSV de cada nodo.
 * Uso: java ... NodeServer <nodeId> <port> <numParts>
 */
public final class NodeServer {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Uso: NodeServer <nodeId> <port> <numParts> (el path data se detecta automáticamente)");
            System.exit(1);
        }

        String nodeId = args[0];
        int port = Integer.parseInt(args[1]);
        int numParts = Integer.parseInt(args[2]);

        // Ruta relativa fija desde este subdirectorio: ../../clients/cli/data
        Path baseData = Path.of("..").resolve("..").resolve("clients").resolve("cli").resolve("data").toAbsolutePath()
                .normalize();
        if (!baseData.resolve(nodeId).resolve("partitions").toFile().exists()) {
            System.out.printf("[NodeServer] Advertencia: no existe %s (asegúrate que el seed escribió en %s)\n",
                    baseData.resolve(nodeId).resolve("partitions"), baseData);
        }
        var client = new LocalFileNodeStorageClient(nodeId, baseData.resolve(nodeId), numParts);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/healthz", new HealthHandler());
        server.createContext("/consultar_cuenta", new ConsultarHandler(client));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.printf("[NodeServer] %s iniciado en puerto %d (base %s)\n", nodeId, port,
                baseData.resolve(nodeId).toString());
    }

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String body = "{\"ok\":true,\"node\":\"up\"}";
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(200, body.getBytes().length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body.getBytes());
            }
        }
    }

    static class ConsultarHandler implements HttpHandler {
        private final LocalFileNodeStorageClient client;

        ConsultarHandler(LocalFileNodeStorageClient client) {
            this.client = client;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                sendResponse(ex, 405, "{\"error\":\"Método no permitido\"}");
                return;
            }

            Map<String, String> q = parseQuery(ex.getRequestURI());
            long id = Long.parseLong(q.getOrDefault("id", "-1"));

            Optional<Account> a = client.getCuenta(id);
            if (a.isPresent()) {
                Account acc = a.get();
                String body = String.format(
                        "{\"ok\":true,\"account\":{\"id\":%d,\"idCliente\":%d,\"saldo\":\"%s\",\"fechaApertura\":\"%s\"}}",
                        acc.id(), acc.idCliente(), acc.saldo().toString(), acc.fechaApertura().toString());
                sendResponse(ex, 200, body);
            } else {
                sendResponse(ex, 404, "{\"ok\":false,\"error\":\"NOT_FOUND\"}");
            }
        }
    }

    static void sendResponse(HttpExchange ex, int code, String body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, body.getBytes().length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body.getBytes());
        }
    }

    static Map<String, String> parseQuery(URI uri) {
        Map<String, String> map = new HashMap<>();
        String query = uri.getRawQuery();
        if (query == null)
            return map;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                map.put(URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    // Nota: usa la ruta relativa fija ../../clients/cli/data desde el directorio
    // actual
}
