/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cc4p1.coordinator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Reenvía consultas a las réplicas en orden de prioridad.
 * Intenta cada nodo hasta recibir 200; diferencia entre 404 (NOT_FOUND)
 * y fallos de conexión (nodos no disponibles).
 */

/**
 *
 * @author Camila
 */

public class WorkerForwarder {
    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(700))
            .build();

    public static String forwardQuery(List<NodeInfo> replicas, int accountId) {
        boolean any404 = false;
        for (NodeInfo node : replicas) {
            String url = String.format("http://%s:%d/consultar_cuenta?id=%d",
                    node.getHost(), node.getPort(), accountId);

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMillis(1300))
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int code = response.statusCode();

                if (code == 200) {
                    System.out.printf("[Forwarder] Nodo %s respondió %d%n", node, code);
                    return response.body();
                }

                if (code == 404) {
                    // No existe en esta réplica, probar la siguiente
                    System.out.printf("[Forwarder] Nodo %s respondió 404, probando siguiente%n", node);
                    any404 = true;
                    continue;
                }

                System.out.printf("[Forwarder] Nodo %s devolvió código %d%n", node, code);

            } catch (IOException | InterruptedException e) {
                System.out.printf("[Forwarder] Falló nodo %s (%s)%n", node, e.getClass().getSimpleName());
            }
        }

        // Si llegamos aquí, ninguna réplica respondió 200.
        // Si al menos una respondió 404, devolvemos NOT_FOUND; si ninguna respondió
        // (fallos de conexión), NODOS_NO_DISPONIBLES.
        if (any404)
            return "{\"ok\":false,\"error\":\"NOT_FOUND\"}";
        return "{\"ok\":false,\"error\":\"NODOS_NO_DISPONIBLES\"}";
    }

    /**
     * Reenvía transferencia al nodo primario de la partición del origen.
     * Solo intenta el primario (no reintentos en réplicas para escrituras).
     * Retorna el cuerpo de la respuesta o un JSON de error.
     */
    public static String forwardTransfer(NodeInfo primary, int origen, int destino, double monto, String txId) {
        String url = String.format("http://%s:%d/transferir_cuenta?origen=%d&destino=%d&monto=%.2f&txId=%s",
                primary.getHost(), primary.getPort(), origen, destino, monto, txId);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(1300))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            long start = System.currentTimeMillis();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long duration = System.currentTimeMillis() - start;
            int code = response.statusCode();

            System.out.printf("[Forwarder] Transfer txId=%s → nodo %s respondió %d en %dms%n",
                    txId, primary, code, duration);

            // Retornamos el body tal cual (puede ser 200, 404, 409, 400, etc.)
            return response.body();

        } catch (IOException | InterruptedException e) {
            System.out.printf("[Forwarder] Transfer txId=%s falló en nodo %s (%s)%n",
                    txId, primary, e.getClass().getSimpleName());
            return "{\"ok\":false,\"error\":\"NODOS_NO_DISPONIBLES\"}";
        }
    }

    /**
     * Reenvía consulta de estado de préstamo a las réplicas en orden de prioridad.
     * Intenta primario → réplica1 → réplica2 hasta recibir 200.
     * Diferencia entre 404 (cuenta sin préstamos o no existe) y fallos de conexión.
     */
    public static String forwardPrestamo(List<NodeInfo> replicas, int cuentaId) {
        boolean any404 = false;
        for (NodeInfo node : replicas) {
            String url = String.format("http://%s:%d/estado_prestamo?id=%d",
                    node.getHost(), node.getPort(), cuentaId);

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMillis(1300))
                        .GET()
                        .build();

                long start = System.currentTimeMillis();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                long duration = System.currentTimeMillis() - start;
                int code = response.statusCode();

                if (code == 200) {
                    System.out.printf("[Forwarder] Prestamo id=%d → nodo %s respondió %d en %dms%n",
                            cuentaId, node, code, duration);
                    return response.body();
                }

                if (code == 404) {
                    System.out.printf("[Forwarder] Prestamo id=%d → nodo %s respondió 404, probando siguiente%n",
                            cuentaId, node);
                    any404 = true;
                    continue;
                }

                System.out.printf("[Forwarder] Prestamo id=%d → nodo %s devolvió código %d%n",
                        cuentaId, node, code);

            } catch (IOException | InterruptedException e) {
                System.out.printf("[Forwarder] Prestamo id=%d falló en nodo %s (%s)%n",
                        cuentaId, node, e.getClass().getSimpleName());
            }
        }

        if (any404)
            return "{\"ok\":false,\"error\":\"CUENTA_SIN_PRESTAMOS\"}";
        return "{\"ok\":false,\"error\":\"NODOS_NO_DISPONIBLES\"}";
    }
}
