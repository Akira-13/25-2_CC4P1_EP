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
    // Parseo simple de argumentos estilo --key=value o --key value
    java.util.Map<String, String> cli = new java.util.HashMap<>();
    for (int i = 0; i < args.length; i++) {
      String a = args[i];
      if ("-h".equals(a) || "--help".equals(a)) {
        printUsageAndExit();
      }
      if (a.startsWith("--")) {
        String kv = a.substring(2);
        int eq = kv.indexOf('=');
        if (eq > 0) {
          String k = kv.substring(0, eq);
          String v = kv.substring(eq + 1);
          cli.put(k, v);
        } else if (i + 1 < args.length) {
          cli.put(kv, args[++i]);
        } else {
          System.err.println("Valor faltante para argumento: " + a);
          printUsageAndExit();
        }
      }
    }

    String nodeId = cli.getOrDefault("nodeId", System.getProperty("nodeId", "nodeA"));
    String host = cli.getOrDefault("host", System.getProperty("host", "127.0.0.1"));
    int port = Integer.parseInt(cli.getOrDefault("port", System.getProperty("port", "9091")));
    int parts = Integer.parseInt(cli.getOrDefault("parts", System.getProperty("parts", "3")));
    String coord = cli.getOrDefault("coord",
        cli.getOrDefault("coordinator", System.getProperty("coord", "http://127.0.0.1:8080")));
    String partList = cli.getOrDefault("partitions", System.getProperty("partitions", "0,1,2"));

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

  private static void printUsageAndExit() {
    String usage = String.join(System.lineSeparator(),
        "Uso: java -cp <classes> cc4p1.worker.WorkerMain [opciones]",
        "Opciones:",
        "  --nodeId <id>             (default: nodeA)",
        "  --host <host>             (default: 127.0.0.1)",
        "  --port <puerto>           (default: 9091)",
        "  --parts <n>               Numero de particiones global (default: 3)",
        "  --partitions <lista>      Particiones que sirve este nodo, ej: 0,1,2 (default: 0,1,2)",
        "  --coord <url>             URL del coordinador, ej: http://127.0.0.1:8080",
        "  --help                     Muestra esta ayuda",
        "\nTambién podés usar -DnodeId=..., -Dhost=..., etc. como fallback.");
    System.out.println(usage);
    System.exit(0);
  }
}
