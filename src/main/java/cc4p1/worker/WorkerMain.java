/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cc4p1.worker;

import java.net.http.*;
import java.net.*;
import java.time.Duration;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class WorkerMain {

    public static void main(String[] args) throws Exception {
        String nodeId = System.getProperty("nodeId", "nodeA");
        String host   = System.getProperty("host", "127.0.0.1");
        int    port   = Integer.parseInt(System.getProperty("port", "9091"));
        int    parts  = Integer.parseInt(System.getProperty("parts", "3"));
        String coord  = System.getProperty("coord", "http://127.0.0.1:8080");
        String partList = System.getProperty("partitions", "0,1,2");

        var srv = new WorkerServer(nodeId, host, port, Path.of("data", nodeId), parts);
        srv.start();

        ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor();
        ses.scheduleAtFixedRate(() -> {
            try {
                String url = String.format("%s/register?host=%s&port=%d&role=replica&partitions=%s",
                        coord, host, port, partList);
                var req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(3))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();
                var client = HttpClient.newHttpClient();
                var res = client.send(req, HttpResponse.BodyHandlers.ofString());
                System.out.println("[Worker " + nodeId + "] register -> " + res.statusCode() + " " + res.body());
            } catch (Exception e) {
                System.out.println("[Worker " + nodeId + "] WARN: no pude registrar en coordinador: " + e.getMessage());
            }
        }, 0, 30, TimeUnit.SECONDS);
    }
}
