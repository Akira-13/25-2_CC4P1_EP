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
 *
 * @author Camila
 */
public class WorkerForwarder {
    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(700))
            .build(); 
    
     public static String forwardQuery(List<NodeInfo> replicas, int accountId) {
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

                
                if (code == 200 || code == 404) {
                    System.out.printf("[Forwarder] Nodo %s respondió %d%n", node, code);
                    return response.body();
                }

                System.out.printf("[Forwarder] Nodo %s devolvió código %d%n", node, code);

            } catch (IOException | InterruptedException e) {
                System.out.printf("[Forwarder] Falló nodo %s (%s)%n", node, e.getClass().getSimpleName());
            }
        }

        
        return "{\"ok\":false,\"error\":\"NODOS_NO_DISPONIBLES\"}";
    }
}
