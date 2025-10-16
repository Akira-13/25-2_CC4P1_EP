package cc4p1.clients.cli;

import cc4p1.storage.tools.SeedReplicated;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * Interfaz de línea de comandos mínima para pruebas.
 * Comandos:
 * - init-cuentas [N] : crea N cuentas replicadas (usa SeedReplicated)
 * - consultar <id> --coordinator=host:port : consulta una cuenta al coordinator
 * - transferir <origen> <destino> <monto> --coordinator=host:port [--txid=ID]
 * - prestamo-crear <idCliente> <monto> [--tasa=0.25] --coordinator=host:port
 * [--loanid=ID]
 * - prestamo-estado <idCuenta> --coordinator=host:port
 * - consultar-transacciones <idCuenta> --coordinator=host:port
 * - loadgen --coordinator=host:port --threads=N --durationSec=S
 * --ops=transfer|loan|mixed --rate=Rps --min=MIN --max=MAX --delayMsMin=A
 * --delayMsMax=B --out=results.csv [--from=ID --to=ID | --accountRange=MIN:MAX]
 */
public final class BankCli {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(1);
        }

        String cmd = args[0];
        switch (cmd) {
            case "loadgen" -> {
                // Pasa el resto de args directamente al LoadGenerator
                String[] subArgs = new String[Math.max(0, args.length - 1)];
                if (args.length > 1)
                    System.arraycopy(args, 1, subArgs, 0, args.length - 1);
                try {
                    LoadGenerator.main(subArgs);
                } catch (Exception e) {
                    System.err.println("Error ejecutando loadgen: " + e.getMessage());
                    System.exit(6);
                }
            }
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
            case "transferir" -> {
                if (args.length < 4) {
                    System.err
                            .println("Uso: transferir <origen> <destino> <monto> --coordinator=host:port [--txid=ID]");
                    System.exit(2);
                }
                String coord = null;
                String txId = null;
                String origenStr = null, destinoStr = null, montoStr = null;
                int posCount = 0;
                for (int i = 1; i < args.length; i++) {
                    String a = args[i];
                    if (a.startsWith("--coordinator=")) {
                        coord = a.substring("--coordinator=".length());
                        continue;
                    }
                    if (a.startsWith("--txid=")) {
                        txId = a.substring("--txid=".length());
                        continue;
                    }
                    // toma los tres posicionales no-flag
                    if (!a.startsWith("--")) {
                        if (posCount == 0)
                            origenStr = a;
                        else if (posCount == 1)
                            destinoStr = a;
                        else if (posCount == 2)
                            montoStr = a;
                        posCount++;
                    }
                }
                if (coord == null || origenStr == null || destinoStr == null || montoStr == null) {
                    System.err.println(
                            "Faltan argumentos. Uso: transferir <origen> <destino> <monto> --coordinator=host:port [--txid=ID]");
                    System.exit(2);
                }
                long origen, destino;
                double monto;
                try {
                    origen = Long.parseLong(origenStr);
                    destino = Long.parseLong(destinoStr);
                    monto = Double.parseDouble(montoStr);
                } catch (NumberFormatException nfe) {
                    System.err.println("Parámetros numéricos inválidos");
                    System.exit(2);
                    return;
                }
                if (txId == null || txId.isBlank())
                    txId = "tx-" + UUID.randomUUID();
                String url = String.format("http://%s/transferir_cuenta?origen=%d&destino=%d&monto=%.2f&txId=%s", coord,
                        origen, destino, monto, txId);
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(700)).build();
                HttpRequest req = HttpRequest.newBuilder().POST(HttpRequest.BodyPublishers.noBody())
                        .uri(URI.create(url)).timeout(Duration.ofSeconds(2)).build();
                try {
                    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                    System.out.println(resp.body());
                    System.exit(resp.statusCode() == 200 ? 0 : 5);
                } catch (IOException | InterruptedException e) {
                    System.err.println("Error realizando transferencia: " + e.getMessage());
                    System.exit(4);
                }
            }
            case "prestamo-crear" -> {
                if (args.length < 3) {
                    System.err.println(
                            "Uso: prestamo-crear <idCliente> <monto> [--tasa=0.25] --coordinator=host:port [--loanid=ID]");
                    System.exit(2);
                }
                String coord = null;
                String tasa = "0.25";
                String loanId = null;
                String idClienteStr = null, montoStr = null;
                int posCount = 0;
                for (int i = 1; i < args.length; i++) {
                    String a = args[i];
                    if (a.startsWith("--coordinator=")) {
                        coord = a.substring("--coordinator=".length());
                        continue;
                    }
                    if (a.startsWith("--tasa=")) {
                        tasa = a.substring("--tasa=".length());
                        continue;
                    }
                    if (a.startsWith("--loanid=")) {
                        loanId = a.substring("--loanid=".length());
                        continue;
                    }
                    if (!a.startsWith("--")) {
                        if (posCount == 0)
                            idClienteStr = a;
                        else if (posCount == 1)
                            montoStr = a;
                        posCount++;
                    }
                }
                if (coord == null || idClienteStr == null || montoStr == null) {
                    System.err.println(
                            "Faltan argumentos. Uso: prestamo-crear <idCliente> <monto> [--tasa=0.25] --coordinator=host:port [--loanid=ID]");
                    System.exit(2);
                }
                int idCliente;
                double monto;
                double tasaAnual;
                try {
                    idCliente = Integer.parseInt(idClienteStr);
                    monto = Double.parseDouble(montoStr);
                    tasaAnual = Double.parseDouble(tasa);
                } catch (NumberFormatException nfe) {
                    System.err.println("Parámetros numéricos inválidos");
                    System.exit(2);
                    return;
                }
                if (loanId == null || loanId.isBlank())
                    loanId = "loan-" + System.currentTimeMillis();
                String url = String.format("http://%s/crear_prestamo?idCliente=%d&monto=%.2f&tasaAnual=%.4f&loanId=%s",
                        coord, idCliente, monto, tasaAnual, loanId);
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(700)).build();
                HttpRequest req = HttpRequest.newBuilder().POST(HttpRequest.BodyPublishers.noBody())
                        .uri(URI.create(url)).timeout(Duration.ofSeconds(2)).build();
                try {
                    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                    System.out.println(resp.body());
                    System.exit(resp.statusCode() == 200 ? 0 : 5);
                } catch (IOException | InterruptedException e) {
                    System.err.println("Error creando préstamo: " + e.getMessage());
                    System.exit(4);
                }
            }
            case "prestamo-estado" -> {
                if (args.length < 2) {
                    System.err.println("Uso: prestamo-estado <idCuenta> --coordinator=host:port");
                    System.exit(2);
                }
                String coord = null;
                String idStr = null;
                for (int i = 1; i < args.length; i++) {
                    String a = args[i];
                    if (a.startsWith("--coordinator=")) {
                        coord = a.substring("--coordinator=".length());
                        continue;
                    }
                    if (!a.startsWith("--") && idStr == null)
                        idStr = a;
                }
                if (coord == null || idStr == null) {
                    System.err.println("Faltan argumentos. Uso: prestamo-estado <idCuenta> --coordinator=host:port");
                    System.exit(2);
                }
                long idCuenta;
                try {
                    idCuenta = Long.parseLong(idStr);
                } catch (NumberFormatException nfe) {
                    System.err.println("idCuenta inválido");
                    System.exit(2);
                    return;
                }
                String url = String.format("http://%s/estado_prestamo?id=%d", coord, idCuenta);
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(700)).build();
                HttpRequest req = HttpRequest.newBuilder().GET().uri(URI.create(url)).timeout(Duration.ofSeconds(2))
                        .build();
                try {
                    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                    System.out.println(resp.body());
                    System.exit(resp.statusCode() == 200 ? 0 : 5);
                } catch (IOException | InterruptedException e) {
                    System.err.println("Error consultando préstamo: " + e.getMessage());
                    System.exit(4);
                }
            }
            case "consultar-transacciones" -> {
                if (args.length < 2) {
                    System.err.println("Uso: consultar-transacciones <idCuenta> --coordinator=host:port");
                    System.exit(2);
                }
                String coord = null;
                String idStr = null;
                for (int i = 1; i < args.length; i++) {
                    String a = args[i];
                    if (a.startsWith("--coordinator=")) {
                        coord = a.substring("--coordinator=".length());
                        continue;
                    }
                    if (!a.startsWith("--") && idStr == null)
                        idStr = a;
                }
                if (coord == null || idStr == null) {
                    System.err.println(
                            "Faltan argumentos. Uso: consultar-transacciones <idCuenta> --coordinator=host:port");
                    System.exit(2);
                }
                long idCuenta;
                try {
                    idCuenta = Long.parseLong(idStr);
                } catch (NumberFormatException nfe) {
                    System.err.println("idCuenta inválido");
                    System.exit(2);
                    return;
                }
                String url = String.format("http://%s/consultar_transacciones?id=%d", coord, idCuenta);
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(700)).build();
                HttpRequest req = HttpRequest.newBuilder().GET().uri(URI.create(url)).timeout(Duration.ofSeconds(2))
                        .build();
                try {
                    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                    System.out.println(resp.body());
                    System.exit(resp.statusCode() == 200 ? 0 : 5);
                } catch (IOException | InterruptedException e) {
                    System.err.println("Error consultando transacciones: " + e.getMessage());
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
        System.out.println("Uso:" +
                "\n  init-cuentas [N]" +
                "\n  consultar <id> --coordinator=host:port" +
                "\n  transferir <origen> <destino> <monto> --coordinator=host:port [--txid=ID]" +
                "\n  prestamo-crear <idCliente> <monto> [--tasa=0.25] --coordinator=host:port [--loanid=ID]" +
                "\n  prestamo-estado <idCuenta> --coordinator=host:port" +
                "\n  consultar-transacciones <idCuenta> --coordinator=host:port" +
                "\n  loadgen --coordinator=host:port --threads=N --durationSec=S --ops=transfer|loan|mixed --rate=Rps --min=MIN --max=MAX --delayMsMin=A --delayMsMax=B --out=results.csv [--from=ID --to=ID | --accountRange=MIN:MAX]");
    }
}
