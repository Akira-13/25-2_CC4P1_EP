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

    /**
     * Resultado de una operación de forwarding con información de failover.
     */
    public static class ForwardResult {
        public final String body;
        public final boolean usedFailover;
        public final int replicaIndex;
        
        public ForwardResult(String body, boolean usedFailover, int replicaIndex) {
            this.body = body;
            this.usedFailover = usedFailover;
            this.replicaIndex = replicaIndex;
        }
    }

    public static ForwardResult forwardQueryWithMetrics(List<NodeInfo> replicas, int accountId) {
        boolean any404 = false;
        int replicaIndex = 0;
        
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
                    if (replicaIndex > 0) {
                        System.out.printf("[Failover] ✓ Cuenta %d respondida por réplica %d (%s) tras fallo de primario%n",
                                accountId, replicaIndex, node);
                    } else {
                        System.out.printf("[Forwarder] Nodo primario %s respondió %d%n", node, code);
                    }
                    return new ForwardResult(response.body(), replicaIndex > 0, replicaIndex);
                }

                if (code == 404) {
                    System.out.printf("[Forwarder] Nodo %s respondió 404, probando siguiente réplica%n", node);
                    any404 = true;
                    replicaIndex++;
                    continue;
                }

                // Errores 5xx: reintentar en siguiente réplica
                if (code >= 500 && code < 600) {
                    System.out.printf("[Failover] Nodo %s respondió %d (error servidor), probando siguiente réplica%n", node, code);
                    replicaIndex++;
                    continue;
                }

                System.out.printf("[Forwarder] Nodo %s devolvió código %d (no reintentar)%n", node, code);
                return new ForwardResult(response.body(), replicaIndex > 0, replicaIndex);

            } catch (IOException | InterruptedException e) {
                System.out.printf("[Failover] Nodo %s falló (%s: %s), probando siguiente réplica%n",
                        node, e.getClass().getSimpleName(), e.getMessage());
                replicaIndex++;
            }
        }

        // Si llegamos aquí, ninguna réplica respondió 200
        String errorBody;
        if (any404) {
            errorBody = "{\"ok\":false,\"error\":\"NOT_FOUND\"}";
        } else {
            errorBody = "{\"ok\":false,\"error\":\"NODOS_NO_DISPONIBLES\"}";
        }
        System.out.printf("[Failover] Todas las réplicas fallaron para cuenta %d (intentos: %d)%n",
                accountId, replicaIndex);
        return new ForwardResult(errorBody, replicaIndex > 0, replicaIndex);
    }

    // Versión legacy para compatibilidad
    public static String forwardQuery(List<NodeInfo> replicas, int accountId) {
        return forwardQueryWithMetrics(replicas, accountId).body;
    }

    /**
     * Reenvía transferencia con failover inteligente:
     * - Reintentar en réplicas SOLO si hay fallo de red/timeout (IOException, InterruptedException)
     * - NO reintentar si el nodo responde con error de negocio (400, 404, 409)
     * - Retorna resultado con información de failover
     */
    public static ForwardResult forwardTransferWithFailover(List<NodeInfo> replicas, int origen, int destino, double monto, String txId) {
        int replicaIndex = 0;
        
        for (NodeInfo node : replicas) {
            String url = String.format("http://%s:%d/transferir_cuenta?origen=%d&destino=%d&monto=%.2f&txId=%s",
                    node.getHost(), node.getPort(), origen, destino, monto, txId);

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

                // Errores de negocio: NO reintentar (la operación llegó al nodo)
                if (code == 400 || code == 404 || code == 409) {
                    System.out.printf("[Forwarder] Transfer txId=%s → nodo %s respondió %d (error de negocio, no reintentar) en %dms%n",
                            txId, node, code, duration);
                    return new ForwardResult(response.body(), replicaIndex > 0, replicaIndex);
                }

                // Errores 5xx: reintentar en siguiente réplica
                if (code >= 500 && code < 600) {
                    System.out.printf("[Failover] Transfer txId=%s → nodo %s respondió %d (error servidor), probando siguiente réplica%n",
                            txId, node, code);
                    replicaIndex++;
                    continue;
                }

                // Éxito (200) o cualquier otro código
                if (replicaIndex > 0) {
                    System.out.printf("[Failover] ✓ Transfer txId=%s completada por réplica %d (%s) con código %d en %dms%n",
                            txId, replicaIndex, node, code, duration);
                } else {
                    System.out.printf("[Forwarder] Transfer txId=%s → nodo primario %s respondió %d en %dms%n",
                            txId, node, code, duration);
                }
                return new ForwardResult(response.body(), replicaIndex > 0, replicaIndex);

            } catch (IOException | InterruptedException e) {
                // Fallo de red/timeout: SÍ reintentar (la operación NO llegó al nodo)
                System.out.printf("[Failover] Transfer txId=%s falló en nodo %s (%s: %s), probando siguiente réplica%n",
                        txId, node, e.getClass().getSimpleName(), e.getMessage());
                replicaIndex++;
            }
        }

        // Todas las réplicas fallaron
        System.out.printf("[Failover] Transfer txId=%s falló en todas las réplicas (intentos: %d)%n",
                txId, replicaIndex);
        return new ForwardResult("{\"ok\":false,\"error\":\"NODOS_NO_DISPONIBLES\"}", replicaIndex > 0, replicaIndex);
    }

    /**
     * Versión legacy sin failover (solo primario) - mantener para compatibilidad
     */
    public static String forwardTransfer(NodeInfo primary, int origen, int destino, double monto, String txId) {
        List<NodeInfo> singleNode = List.of(primary);
        return forwardTransferWithFailover(singleNode, origen, destino, monto, txId).body;
    }

    /**
     * Reenvía consulta de estado de préstamo con failover completo.
     * Intenta primario → réplica1 → réplica2 hasta recibir 200.
     * Diferencia entre 404 (cuenta sin préstamos) y fallos de conexión/5xx.
     */
    public static ForwardResult forwardPrestamoWithMetrics(List<NodeInfo> replicas, int cuentaId) {
        boolean any404 = false;
        int replicaIndex = 0;
        
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
                    if (replicaIndex > 0) {
                        System.out.printf("[Failover] ✓ Prestamo id=%d respondido por réplica %d (%s) en %dms%n",
                                cuentaId, replicaIndex, node, duration);
                    } else {
                        System.out.printf("[Forwarder] Prestamo id=%d → nodo primario %s respondió %d en %dms%n",
                                cuentaId, node, code, duration);
                    }
                    return new ForwardResult(response.body(), replicaIndex > 0, replicaIndex);
                }

                if (code == 404) {
                    System.out.printf("[Forwarder] Prestamo id=%d → nodo %s respondió 404, probando siguiente réplica%n",
                            cuentaId, node);
                    any404 = true;
                    replicaIndex++;
                    continue;
                }

                // Errores 5xx: reintentar en siguiente réplica
                if (code >= 500 && code < 600) {
                    System.out.printf("[Failover] Prestamo id=%d → nodo %s respondió %d (error servidor), probando siguiente réplica%n",
                            cuentaId, node, code);
                    replicaIndex++;
                    continue;
                }

                System.out.printf("[Forwarder] Prestamo id=%d → nodo %s devolvió código %d (no reintentar)%n",
                        cuentaId, node, code);
                return new ForwardResult(response.body(), replicaIndex > 0, replicaIndex);

            } catch (IOException | InterruptedException e) {
                System.out.printf("[Failover] Prestamo id=%d falló en nodo %s (%s: %s), probando siguiente réplica%n",
                        cuentaId, node, e.getClass().getSimpleName(), e.getMessage());
                replicaIndex++;
            }
        }

        // Todas las réplicas fallaron
        String errorBody;
        if (any404) {
            errorBody = "{\"ok\":false,\"error\":\"CUENTA_SIN_PRESTAMOS\"}";
        } else {
            errorBody = "{\"ok\":false,\"error\":\"NODOS_NO_DISPONIBLES\"}";
        }
        System.out.printf("[Failover] Prestamo id=%d falló en todas las réplicas (intentos: %d)%n",
                cuentaId, replicaIndex);
        return new ForwardResult(errorBody, replicaIndex > 0, replicaIndex);
    }

    // Versión legacy para compatibilidad
    public static String forwardPrestamo(List<NodeInfo> replicas, int cuentaId) {
        return forwardPrestamoWithMetrics(replicas, cuentaId).body;
    }

    /**
     * Reenvía consulta de transacciones de una cuenta con failover completo.
     * Intenta primario → réplica1 → réplica2 hasta recibir 200.
     */
    public static ForwardResult forwardConsultarTransaccionesWithMetrics(List<NodeInfo> replicas, int cuentaId) {
        boolean any404 = false;
        int replicaIndex = 0;
        
        for (NodeInfo node : replicas) {
            String url = String.format("http://%s:%d/consultar_transacciones?id=%d",
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
                    if (replicaIndex > 0) {
                        System.out.printf("[Failover] ✓ Transacciones id=%d respondidas por réplica %d (%s) en %dms%n",
                                cuentaId, replicaIndex, node, duration);
                    } else {
                        System.out.printf("[Forwarder] Transacciones id=%d → nodo primario %s respondió %d en %dms%n",
                                cuentaId, node, code, duration);
                    }
                    return new ForwardResult(response.body(), replicaIndex > 0, replicaIndex);
                }

                if (code == 404) {
                    System.out.printf("[Forwarder] Transacciones id=%d → nodo %s respondió 404, probando siguiente réplica%n",
                            cuentaId, node);
                    any404 = true;
                    replicaIndex++;
                    continue;
                }

                // Errores 5xx: reintentar en siguiente réplica
                if (code >= 500 && code < 600) {
                    System.out.printf("[Failover] Transacciones id=%d → nodo %s respondió %d (error servidor), probando siguiente réplica%n",
                            cuentaId, node, code);
                    replicaIndex++;
                    continue;
                }

                System.out.printf("[Forwarder] Transacciones id=%d → nodo %s devolvió código %d (no reintentar)%n",
                        cuentaId, node, code);
                return new ForwardResult(response.body(), replicaIndex > 0, replicaIndex);

            } catch (IOException | InterruptedException e) {
                System.out.printf("[Failover] Transacciones id=%d falló en nodo %s (%s: %s), probando siguiente réplica%n",
                        cuentaId, node, e.getClass().getSimpleName(), e.getMessage());
                replicaIndex++;
            }
        }

        // Todas las réplicas fallaron
        String errorBody;
        if (any404) {
            errorBody = "{\"ok\":false,\"error\":\"CUENTA_SIN_TRANSACCIONES\"}";
        } else {
            errorBody = "{\"ok\":false,\"error\":\"NODOS_NO_DISPONIBLES\"}";
        }
        System.out.printf("[Failover] Transacciones id=%d falló en todas las réplicas (intentos: %d)%n",
                cuentaId, replicaIndex);
        return new ForwardResult(errorBody, replicaIndex > 0, replicaIndex);
    }

    /**
     * Reenvía creación de préstamo con failover inteligente:
     * - Reintentar en réplicas SOLO si hay fallo de red/timeout (IOException, InterruptedException)
     * - NO reintentar si el nodo responde con error de negocio (400, 404, 409)
     * - Retorna resultado con información de failover
     */
    public static ForwardResult forwardCrearPrestamoWithFailover(List<NodeInfo> replicas, int idCliente, double monto, double tasaAnual, String loanId) {
        int replicaIndex = 0;
        
        for (NodeInfo node : replicas) {
            String url = String.format("http://%s:%d/crear_prestamo?idCliente=%d&monto=%.2f&tasaAnual=%.4f&loanId=%s",
                    node.getHost(), node.getPort(), idCliente, monto, tasaAnual, loanId);

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

                // Errores de negocio: NO reintentar (la operación llegó al nodo)
                if (code == 400 || code == 404 || code == 409) {
                    System.out.printf("[Forwarder] CrearPrestamo loanId=%s → nodo %s respondió %d (error de negocio, no reintentar) en %dms%n",
                            loanId, node, code, duration);
                    return new ForwardResult(response.body(), replicaIndex > 0, replicaIndex);
                }

                // Errores 5xx: reintentar en siguiente réplica
                if (code >= 500 && code < 600) {
                    System.out.printf("[Failover] CrearPrestamo loanId=%s → nodo %s respondió %d (error servidor), probando siguiente réplica%n",
                            loanId, node, code);
                    replicaIndex++;
                    continue;
                }

                // Éxito (200) o cualquier otro código
                if (replicaIndex > 0) {
                    System.out.printf("[Failover] ✓ CrearPrestamo loanId=%s completado por réplica %d (%s) con código %d en %dms%n",
                            loanId, replicaIndex, node, code, duration);
                } else {
                    System.out.printf("[Forwarder] CrearPrestamo loanId=%s → nodo primario %s respondió %d en %dms%n",
                            loanId, node, code, duration);
                }
                return new ForwardResult(response.body(), replicaIndex > 0, replicaIndex);

            } catch (IOException | InterruptedException e) {
                // Fallo de red/timeout: SÍ reintentar (la operación NO llegó al nodo)
                System.out.printf("[Failover] CrearPrestamo loanId=%s falló en nodo %s (%s: %s), probando siguiente réplica%n",
                        loanId, node, e.getClass().getSimpleName(), e.getMessage());
                replicaIndex++;
            }
        }

        // Todas las réplicas fallaron
        System.out.printf("[Failover] CrearPrestamo loanId=%s falló en todas las réplicas (intentos: %d)%n",
                loanId, replicaIndex);
        return new ForwardResult("{\"ok\":false,\"error\":\"NODOS_NO_DISPONIBLES\"}", replicaIndex > 0, replicaIndex);
    }
}
