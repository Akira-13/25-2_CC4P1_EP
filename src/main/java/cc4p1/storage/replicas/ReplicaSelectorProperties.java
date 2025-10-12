package cc4p1.storage.replicas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Carga mapeos de réplicas desde un .properties usando solo Java SE */
public final class ReplicaSelectorProperties implements ReplicaSelector {
  // tabla -> (partición -> orden de nodos)
  private final Map<String, Map<Integer, List<String>>> map = new HashMap<>();

  public ReplicaSelectorProperties(Path propsFile) {
    Properties props = new Properties();
    try (var in = Files.newInputStream(propsFile)) {
      props.load(in);
    } catch (IOException e) {
      throw new RuntimeException("No se pudo leer " + propsFile, e);
    }

    for (String key : props.stringPropertyNames()) {
      // clave esperada: <tabla>.p<numero>, ej: cuentas.p0
      int dot = key.lastIndexOf('.');
      if (dot <= 0 || dot == key.length() - 1) {
        throw new IllegalArgumentException("Clave inválida: " + key);
      }
      String table = key.substring(0, dot).trim();
      String partKey = key.substring(dot + 1).trim(); // "p0"
      if (!partKey.startsWith("p")) {
        throw new IllegalArgumentException("Partición inválida: " + key);
      }
      int partition = Integer.parseInt(partKey.substring(1));
      String raw = props.getProperty(key);
      if (raw == null || raw.isBlank()) {
        throw new IllegalArgumentException("Sin nodos para " + key);
      }
      String[] arr = raw.split(",");
      List<String> nodes = new ArrayList<>(arr.length);
      for (String s : arr) {
        String id = s.trim();
        if (!id.isEmpty()) nodes.add(id);
      }
      if (nodes.isEmpty()) {
        throw new IllegalArgumentException("Lista de nodos vacía para " + key);
      }
      // Validar duplicados
      if (new HashSet<>(nodes).size() != nodes.size()) {
        throw new IllegalArgumentException("Nodos duplicados en " + key + ": " + nodes);
      }
      map.computeIfAbsent(table, t -> new HashMap<>())
         .put(partition, Collections.unmodifiableList(nodes));
    }

    // Inmutabilidad superficial
    for (var e : map.entrySet()) {
      e.setValue(Collections.unmodifiableMap(e.getValue()));
    }
  }

  private List<String> nodesFor(String table, int partition) {
    Map<Integer, List<String>> parts = map.get(table);
    if (parts == null || !parts.containsKey(partition)) {
      throw new IllegalArgumentException("Sin mapeo para table=" + table + " partition=" + partition);
    }
    return parts.get(partition);
  }

  @Override public String primary(String table, int partition) {
    return nodesFor(table, partition).get(0);
  }

  @Override public List<String> readOrder(String table, int partition) {
    return nodesFor(table, partition);
  }

  @Override public int replicaCount(String table, int partition) {
    return nodesFor(table, partition).size();
  }
}
