# README

## Generar datos

### SeedTool

Para generar datos, ejecutar `storage/tools/SeedTool.java`. Este generará 10k datos bancarios en `data/partitions/cuentas_px.csv`.

## Particionar

### SeedReplicated

- Para particionar los registros, ejecutar `storage/tools/SeedReplicated.java`.


## Ejecución completa

- Para una ejecución completa (con registros inciales creados), ejecutar `storage/tools/ReplicatedDemo.java`. Este demo:

    1. Crea el particionador (Partitioner(3)) → te da p ∈ {0,1,2} para cada ID.

    2. Carga el mapeo de réplicas (ReplicaSelectorProperties) desde `data/metadata/replicas.properties` → para cada tabla y partición sabe cuál es el primario y las réplicas.

        - Una copia de un `replicas.properties` por defecto se encuentra en `resources/templates`

    3. Crea los “clientes de nodo” (LocalFileNodeStorageClient) → cada uno envuelve un FileStorage apuntando a una raíz distinta (`data/nodeA`, `data/nodeB`, `data/nodeC`).

    4. Arma la fachada de replicación (ReplicatedStorage) → implementa tu API Storage con:

        - get con failover (primario → réplica1 → réplica2),

        - put con fanout (a las 3),

        - scan leyendo solo 1 nodo por partición (evita duplicados).