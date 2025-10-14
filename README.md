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

# Logs de transacción

- Headers de logs: tx_id;ts;origen;destino;monto;tipo
- Log de transacciones particionado y replicado
    - Cada transacción se almacena en un archivo transacciones_p{n}.csv, donde n es la partición calculada a partir de la cuenta origen.
    - Este log se replica en los tres nodos (nodeA, nodeB, nodeC), de acuerdo al archivo replicas.properties.
- Append atómico
    - Se implementó escritura atómica con FileChannel.lock() y force(true), asegurando que los registros se graben completos, incluso en caso de concurrencia o fallos durante el append.
- Idempotencia por txId
    - Cada transacción incluye un identificador único (txId):
    - Si la misma txId llega otra vez con el mismo contenido, se ignora (evita duplicados por reintentos).
    - Si llega con contenido distinto, se rechaza (detecta conflicto).
- TransactionLogDemo
    - Programa de ejemplo que genera tres transacciones, una por cada partición (p0, p1, p2), verificando la replicación en los tres nodos.
    - Requiere datos iniciales creados.


# Pagos y Préstamos

- Headers de logs: tx_id;ts;origen;destino;monto;tipo
- Log de transacciones particionado y replicado
    - Cada transacción se almacena en un archivo transacciones_p{n}.csv, donde n es la partición calculada a partir de la cuenta origen.
    - Este log se replica en los tres nodos (nodeA, nodeB, nodeC), de acuerdo al archivo replicas.properties.
- Append atómico
    - Se implementó escritura atómica con FileChannel.lock() y force(true), asegurando que los registros se graben completos, incluso en caso de concurrencia o fallos durante el append.
- Idempotencia por txId
    - Cada transacción incluye un identificador único (txId):
    - Si la misma txId llega otra vez con el mismo contenido, se ignora (evita duplicados por reintentos).
    - Si llega con contenido distinto, se rechaza (detecta conflicto).
- TransactionLogDemo
    - Programa de ejemplo que genera tres transacciones, una por cada partición (p0, p1, p2), verificando la replicación en los tres nodos.
    - Requiere datos iniciales creados.


# EJECUCIÓN DEL COORDINADOR

## 1. Generar datos iniciales (opcional)

Antes de iniciar los servicios, puedes crear cuentas de prueba y los archivos necesarios usando el CLI:

```powershell
cd src\main\java\cc4p1\clients\cli
java BankCli.java init-cuentas 100
```

Esto crea 100 cuentas distribuidas en los nodos y genera el archivo `data/metadata/replicas.properties`.

## 2. Iniciar los servicios

### 2.1. Iniciar el coordinador

Desde la raíz del proyecto o desde `src/main/java/cc4p1/coordinator`:

```powershell
cd src\main\java\cc4p1\coordinator
java CoordinatorServer.java
```

### 2.2. Iniciar los nodos

Desde la raíz del proyecto o desde `src/main/java/cc4p1/storage/replicated`:

```powershell
cd src\main\java\cc4p1\storage\replicated
java NodeServer.java nodeA 9001 3
java NodeServer.java nodeB 9002 3
java NodeServer.java nodeC 9003 3
```

## 3. Registrar los nodos en el coordinador

En una terminal de PowerShell, ejecuta:

```powershell
Invoke-WebRequest -Method POST "http://localhost:8080/register?host=localhost&port=9001&partitions=0,1,2&role=replica" -UseBasicParsing
Invoke-WebRequest -Method POST "http://localhost:8080/register?host=localhost&port=9002&partitions=0,1,2&role=replica" -UseBasicParsing
Invoke-WebRequest -Method POST "http://localhost:8080/register?host=localhost&port=9003&partitions=0,1,2&role=replica" -UseBasicParsing
```

## 4. Consultar cuentas usando el CLI

Desde `src/main/java/cc4p1/clients/cli`:

```powershell
java BankCli.java consultar 42 --coordinator=localhost:8080
```

## 5. Endpoints disponibles

### Coordinator

- **POST /register**
  - Registra un nodo.
  - Ejemplo:
    `POST http://localhost:8080/register?host=localhost&port=9001&partitions=0,1,2&role=replica`
- **GET /consultar_cuenta?id=ID**
  - Consulta una cuenta por id (el coordinator reenvía la consulta al nodo correspondiente).
  - Ejemplo:
    `GET http://localhost:8080/consultar_cuenta?id=42`
- **GET /routing**
  - Devuelve el mapeo actual de particiones y nodos registrados.
- **GET /healthz**
  - Verifica que el coordinador está activo.

### Nodo (NodeServer)

- **GET /consultar_cuenta?id=ID**
  - Devuelve la cuenta local si existe.
- **GET /healthz**
  - Verifica que el nodo está activo.

## 6. Respuestas esperadas

- Consulta exitosa:

```json
{"ok":true,"account":{"id":42,"idCliente":42,"saldo":"1000","fechaApertura":"2025-10-13"}}
```

- Cuenta no encontrada:

```json
{"ok":false,"error":"NOT_FOUND"}
```

- Nodo no disponible:

```json
{"ok":false,"error":"NODOS_NO_DISPONIBLES"}
```

---
