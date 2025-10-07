package cc4p1.storage.tools;

import cc4p1.storage.FileStorage;
import java.nio.file.Path;

public final class Archeo {
  public static void main(String[] args) {
    var storage = FileStorage.open(Path.of("data"), 3);
    System.out.println("ARQUEO="+storage.arqueoSaldos());
  }
}
