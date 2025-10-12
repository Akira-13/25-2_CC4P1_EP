package cc4p1.storage;

public class PartitionerDemo {
  public static void main(String[] args) {
    Partitioner p = new Partitioner(3);
    for (long id = 1; id <= 10; id++) {
      System.out.println(id + " -> p" + p.partForId(id));
    }
  }
}
