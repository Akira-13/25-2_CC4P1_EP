package cc4p1.clients.cli;

import cc4p1.storage.tools.SeedReplicated;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Interfaz de línea de comandos mínima para pruebas.
 * Comandos:
 * - init-cuentas [N] : crea N cuentas replicadas (usa SeedReplicated)
 * - consultar <id> --coordinator=host:port : consulta una cuenta al coordinator
 */
public final class BankCli {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(1);
        }

        String cmd = args[0];
        switch (cmd) {
            case "init-cuentas" -> {
                int n = args.length > 1 ? Integer.parseInt(args[1]) : 10_000;
                System.out.println("Sembrando " + n + " cuentas (replicadas) ...");
                // Reutiliza SeedReplicated para crear los ficheros en data/
                String[] a = new String[] { String.valueOf(n) };
                SeedReplicated.main(a);
                System.out.println("Seed completado");
            }
            case "consultar" -> {
                if (args.length < 2) {
                    System.err.println("Falta id");
                    usage();
                    System.exit(2);
                }
                // Parse flexible arguments: soporta
                // - consultar <id> --coordinator=host:port
                // - consultar 42--coordinator=host:port (sin espacio)
                String idStr = null;
                String coord = null;
                for (int i = 1; i < args.length; i++) {
                    String a = args[i];
                    if (a.startsWith("--coordinator=")) {
                        coord = a.substring("--coordinator=".length());
                        continue;
                    }
                    if (a.contains("--coordinator=")) {
                        // caso: "42--coordinator=localhost:8080"
                        String[] sp = a.split("--coordinator=", 2);
                        if (sp.length > 0 && sp[0].length() > 0) {
                            // primera parte puede ser el id
                            idStr = sp[0];
                        }
                        coord = sp.length > 1 ? sp[1] : null;
                        continue;
                    }
                    // si aún no tenemos id, el primer token no-flag será el id
                    if (idStr == null) {
                        idStr = a;
                    }
                }
                if (idStr == null) {
                    System.err.println("No se encontró id en los argumentos");
                    usage();
                    System.exit(2);
                }
                long id;
                try {
                    id = Long.parseLong(idStr);
                } catch (NumberFormatException nfe) {
                    System.err.println("Id inválido: '" + idStr + "' (debe ser un número)");
                    usage();
                    System.exit(2);
                    return;
                }
                if (coord == null || coord.isBlank()) {
                    System.err.println("Debe proveer --coordinator=host:port");
                    usage();
                    System.exit(3);
                }
                String url = String.format("http://%s/consultar_cuenta?id=%d", coord, id);
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
                HttpRequest req = HttpRequest.newBuilder().GET().uri(URI.create(url)).timeout(Duration.ofSeconds(2))
                        .build();
                try {
                    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                    System.out.println(resp.body());
                    System.exit(0);
                } catch (IOException | InterruptedException e) {
                    System.err.println("Error consultando coordinator: " + e.getMessage());
                    System.exit(4);
                }
            }
            default -> {
                System.err.println("Comando desconocido: " + cmd);
                usage();
                System.exit(1);
            }
        }
    }

    static void usage() {
        System.out.println("Uso:\n  init-cuentas [N]\n  consultar <id> --coordinator=host:port");
    }
}
