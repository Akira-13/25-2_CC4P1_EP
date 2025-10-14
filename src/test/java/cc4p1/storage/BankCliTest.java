package cc4p1.storage;

import cc4p1.storage.replicated.ReplicatedStorage;
import cc4p1.storage.replicas.ReplicaSelectorProperties;
import cc4p1.storage.replicated.LocalFileNodeStorageClient;
import cc4p1.storage.Partitioner;
import cc4p1.storage.Storage;
import cc4p1.storage.replicated.NodeStorageClient;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BankCliTest {

    @Test
    public void seedReplicatedCreatesAccounts() throws Exception {
        // Ejecuta SeedReplicated con 100 cuentas
        Path base = Path.of("data");
        // Limpiar data/ previo para evitar acumulación entre ejecuciones del test
        if (Files.exists(base)) {
            try {
                Files.walk(base)
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        String[] args = new String[] { "100" };
        // llamamos directamente al main
        cc4p1.storage.tools.SeedReplicated.main(args);

        // Construimos un ReplicatedStorage y verificamos scan count
        int NUM_PARTS = 3;
        Partitioner partitioner = new Partitioner(NUM_PARTS);
        ReplicaSelectorProperties selector = new ReplicaSelectorProperties(
                base.resolve("metadata").resolve("replicas.properties"));

        Map<String, NodeStorageClient> nodes = new HashMap<>();
        nodes.put("nodeA", new LocalFileNodeStorageClient("nodeA", base.resolve("nodeA"), NUM_PARTS));
        nodes.put("nodeB", new LocalFileNodeStorageClient("nodeB", base.resolve("nodeB"), NUM_PARTS));
        nodes.put("nodeC", new LocalFileNodeStorageClient("nodeC", base.resolve("nodeC"), NUM_PARTS));

        Storage st = new ReplicatedStorage(partitioner, selector, nodes);
        long count = ((ReplicatedStorage) st).scanCuentas().count();

        assertEquals(100, count);
    }
}
