/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cc4p1.worker;

import cc4p1.model.Account;
import cc4p1.storage.FileStorage;
import cc4p1.storage.Partitioner;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;

public final class WorkerServer {
  private final String nodeId;
  private final String host;
  private final int port;
  private final FileStorage storage;
  private HttpServer server;

  public WorkerServer(String nodeId, String host, int port, Path nodeBase, int numParts) throws IOException {
    this.nodeId = nodeId;
    this.host = host;
    this.port = port;
    this.storage = FileStorage.open(nodeBase, numParts); // ya maneja particiones
  }

  public void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(host, port), 128);
    server.createContext("/health", new HealthHandler());
    server.createContext("/consultar_cuenta", new ConsultarCuentaHandler());
    server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));
    server.start();
    System.out.printf("[Worker %s] listening on %s:%d%n", nodeId, host, port);
  }

  public void stop() { if (server != null) server.stop(0); }

  // --- Handlers ---

  final class HealthHandler implements HttpHandler {
    @Override public void handle(HttpExchange ex) throws IOException {
      sendJson(ex, 200, "{\"ok\":true}");
    }
  }

    final class ConsultarCuentaHandler implements HttpHandler {
      @Override public void handle(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
          sendJson(ex, 405, "{\"ok\":false,\"error\":\"METHOD_NOT_ALLOWED\"}");
          return;
        }
        Map<String,String> q = parseQuery(ex.getRequestURI());
        String idStr = q.get("id");
        if (idStr == null) { sendJson(ex, 400, "{\"ok\":false,\"error\":\"MISSING_ID\"}"); return; }

        try {
          long id = Long.parseLong(idStr);
          var accOpt = storage.getCuenta(id);
          if (accOpt.isEmpty()) { sendJson(ex, 404, "{\"ok\":false,\"error\":\"NOT_FOUND\"}"); return; }

          Object a = accOpt.get();

          // Extraer propiedades de forma tolerante al modelo:
          Number accId   = asNumber(invokeAny(a, "id", "getId"));
          Object client  = invokeAny(a, "clientId", "getClientId", "client", "getClient", "customerId", "getCustomerId");
          Object balance = invokeAny(a, "balance", "getBalance", "saldo", "getSaldo", "amount", "getAmount");
          Object opened  = invokeAny(a, "openedAt", "getOpenedAt", "apertura", "getApertura", "createdAt", "getCreatedAt");

          StringBuilder sb = new StringBuilder(128);
          sb.append("{\"ok\":true,\"account\":{");
          // id (si no se obtuvo via reflexión, usamos el solicitado)
          sb.append("\"id\":").append(accId != null ? accId.longValue() : id);

          if (client != null) {
            sb.append(",\"cliente\":").append(jsonValue(client));
          }
          if (balance != null) {
            // BigDecimal → string "123.45" para no perder precisión
            sb.append(",\"saldo\":").append(jsonValue(balance));
          }
          if (opened != null) {
            sb.append(",\"apertura\":").append(jsonValue(opened));
          }
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

    // --- helpers de reflexión y json (añádelos al final de WorkerServer) ---
    static Object invokeAny(Object target, String... methodNames) {
      for (String m : methodNames) {
        try {
          var mm = target.getClass().getMethod(m);
          return mm.invoke(target);
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
          // si el getter existe pero falló, lo propagamos
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

    static String jsonEscape(String s) {
      StringBuilder sb = new StringBuilder(s.length()+8);
      for (int i=0;i<s.length();i++) {
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
            if (c < 0x20) sb.append(String.format("\\u%04x", (int)c));
            else sb.append(c);
        }
      }
      return sb.toString();
    }

    static String jsonValue(Object v) {
      if (v == null) return "null";
      if (v instanceof Number n) return (n instanceof java.math.BigDecimal) ? "\"" + n.toString() + "\"" : n.toString();
      if (v instanceof Boolean b) return b.toString();
      return "\"" + jsonEscape(String.valueOf(v)) + "\"";
    }

  // --- utils ---
  static Map<String,String> parseQuery(URI uri) {
    Map<String,String> map = new HashMap<>();
    String q = uri.getRawQuery();
    if (q == null) return map;
    for (String pair : q.split("&")) {
      int i = pair.indexOf('=');
      if (i > 0) {
        map.put(URLDecoder.decode(pair.substring(0,i), StandardCharsets.UTF_8),
                URLDecoder.decode(pair.substring(i+1), StandardCharsets.UTF_8));
      }
    }
    return map;
  }

  static void sendJson(HttpExchange ex, int code, String body) throws IOException {
    ex.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.sendResponseHeaders(code, b.length);
    try (OutputStream os = ex.getResponseBody()) { os.write(b); }
  }
}

