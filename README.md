
# README

## 1. Generar datos

- Headers de cuentas: id_cuenta;id_cliente;saldo;fecha_apertura

### 1.1. SeedTool

Para generar datos, ejecutar `storage/tools/SeedTool.java`. Este generará 10k datos bancarios en `data/partitions/cuentas_px.csv`.

## 2. Particionar

### 2.1. SeedReplicated

- Para particionar los registros, ejecutar `storage/tools/SeedReplicated.java`.

## 3. Ejecución completa

- Para una ejecución completa (con registros inciales creados), ejecutar `storage/tools/ReplicatedDemo.java`. Este demo:

    1. Crea el particionador (Partitioner(3)) → te da p ∈ {0,1,2} para cada ID.
    2. Carga el mapeo de réplicas (ReplicaSelectorProperties) desde `data/metadata/replicas.properties` → para cada tabla y partición sabe cuál es el primario y las réplicas.
        - Una copia de un `replicas.properties` por defecto se encuentra en `resources/templates`
    3. Crea los “clientes de nodo” (LocalFileNodeStorageClient) → cada uno envuelve un FileStorage apuntando a una raíz distinta (`data/nodeA`, `data/nodeB`, `data/nodeC`).
    4. Arma la fachada de replicación (ReplicatedStorage) → implementa tu API Storage con:
        - get con failover (primario → réplica1 → réplica2),
        - put con fanout (a las 3),
        - scan leyendo solo 1 nodo por partición (evita duplicados).

## 4. Logs de transacción

- Headers de logs: id_tx;id_cuenta;tipo;monto;fecha
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

## 5. Pagos y Préstamos

- Préstamos (Loan)
  - Headers: id_prestamo;id_cliente;monto;tasa_anual;fecha;pendiente;estado
  - Registra el monto total otorgado a un cliente, su tasa anual y fecha de otorgamiento.
  - Guardado en archivos prestamos_p{n}.csv (particionado por id_cliente).
  - Replicado automáticamente en los tres nodos (nodeA, nodeB, nodeC).
  - Se ignoran intereses por ahora
- Pagos (Payment)
  - pay_id;ts;id_prestamo;monto
  - Registra cada abono a un préstamo (monto y timestamp).
  - Guardado en archivos pagos_p{n}.csv (particionado por id_prestamo).
  - También replicado en los tres nodos.
- LoanUtils
  - Clase utilitaria que calcula:
  - Saldo pendiente: monto – sum(pagos) (sin intereses en P1).
  - Estado lógico: "ACTIVO" o "CANCELADO" según el saldo restante.
- LoanDemo
  - Programa que crea un préstamo replicado, registra dos pagos y calcula el saldo pendiente (ejemplo: préstamo de 1000, pagos de 300 y 200 → pendiente 500).

## 6. EJECUCIÓN DEL COORDINADOR

### 6.1. Generar datos iniciales (opcional)

Antes de iniciar los servicios, puedes crear cuentas de prueba y los archivos necesarios usando el CLI:

```powershell
java -cp .\target\classes cc4p1.clients.cli.BankCli init-cuentas 100
```

Esto crea 100 cuentas distribuidas en los nodos y genera el archivo `data/metadata/replicas.properties`.

### 6.2. Iniciar los servicios

#### 6.2.1. Iniciar el coordinador

Desde la raíz del proyecto o desde `src/main/java/cc4p1/coordinator`:

```powershell
java -cp .\target\classes cc4p1.coordinator.CoordinatorServer
```

#### 6.2.2. Iniciar los workers-nodos

Desde la raíz del proyecto (o donde esté el target/classes generado), puedes iniciar cada worker con:

```powershell
java -cp .\target\classes cc4p1.worker.WorkerMain --nodeId nodeA --host 127.0.0.1 --port 9091 --parts 3 --partitions 0,1,2 --coord http://127.0.0.1:8080
```

Para levantar varios nodos en la misma máquina, usa diferentes nodeId y puertos:

### 6.3. Consultas y operaciones principales con el CLI

#### 6.3.1. Consultar cuentas

Desde `src/main/java/cc4p1/clients/cli`:

```powershell
java -cp .\target\classes cc4p1.clients.cli.BankCli consultar 42 --coordinator=localhost:8080
```

#### 6.3.2. Realizar una transferencia

Desde la raíz del proyecto o desde `src/main/java/cc4p1/clients/cli`:

```powershell
java -cp .\target\classes cc4p1.clients.cli.BankCli transferir 1 2 100 --coordinator=127.0.0.1:8080
```

Esto transfiere $100 de la cuenta 1 a la cuenta 2 usando el coordinador.

#### 6.3.3. Crear un préstamo

Desde la raíz del proyecto o desde `src/main/java/cc4p1/clients/cli`:

```powershell
java -cp .\target\classes cc4p1.clients.cli.BankCli prestamo-crear 1 500 --tasa=0.25 --coordinator=127.0.0.1:8080
```

Esto crea un préstamo de $500 para el cliente 1 con una tasa anual de 0.25.

#### 6.3.4. Arqueo (conservación de dinero)

Para sumar los saldos de todas las cuentas (deduplicando por id):

```powershell
java -cp .\target\classes cc4p1.clients.cli.BankCli archeo
```

O bien, especificando nodos:

```powershell
java -cp .\target\classes cc4p1.clients.cli.BankCli archeo --paths=data/nodeA;data/nodeB --parts=3
```

La salida muestra el total de dinero, cantidad de cuentas únicas y filas leídas.

  ```powershell
  java -cp .\target\classes cc4p1.worker.WorkerMain --nodeId nodeB --host 127.0.0.1 --port 9092 --parts 3 --partitions 0,1,2 --coord http://127.0.0.1:8080
  ```

- Nodo C:

  ```powershell
  java -cp .\target\classes cc4p1.worker.WorkerMain --nodeId nodeC --host 127.0.0.1 --port 9093 --parts 3 --partitions 0,1,2 --coord http://127.0.0.1:8080
  ```

Si quieres repartir las particiones (sharding, sin replicación), puedes hacer:

- Nodo A (solo partición 0):

  ```powershell
  java -cp .\target\classes cc4p1.worker.WorkerMain --nodeId nodeA --host 127.0.0.1 --port 9091 --parts 3 --partitions 0 --coord http://127.0.0.1:8080
  ```

- Nodo B (solo partición 1):

  ```powershell
  java -cp .\target\classes cc4p1.worker.WorkerMain --nodeId nodeB --host 127.0.0.1 --port 9092 --parts 3 --partitions 1 --coord http://127.0.0.1:8080
  ```

- Nodo C (solo partición 2):

  ```powershell
  java -cp .\target\classes cc4p1.worker.WorkerMain --nodeId nodeC --host 127.0.0.1 --port 9093 --parts 3 --partitions 2 --coord http://127.0.0.1:8080
  ```

Cada worker usará su propia carpeta de datos (data/nodeA, data/nodeB, etc). Puedes correr cada comando en una terminal diferente.

## 7. Endpoints disponibles

### 7.1. Coordinator (Coordinador)

#### 7.1.1. Administración y monitoreo

- **POST /register?host=&port=&partitions=&role=**
  - Registra un nodo worker en la tabla de ruteo.
  - Parámetros: `host`, `port`, `partitions` (separadas por coma), `role` (primary/replica)
  - Ejemplo:
    `POST http://localhost:8080/register?host=localhost&port=9001&partitions=0,1,2&role=primary`
- **GET /routing**
  - Devuelve el mapeo actual de particiones y nodos registrados.
  - Respuesta: `{"ok":true,"routing":{"0":[{"host":"localhost","port":9001,"priority":0},...]}}`
- **GET /healthz**
  - Verifica que el coordinador está activo.
  - Respuesta: `{"ok":true,"msg":"coordinator up"}`
- **GET /metrics**
  - Devuelve métricas del coordinador.
  - Respuesta: `{"ok":true,"metrics":{"req_total":100,"fallbacks_total":5,"errors_total":2}}`

#### 7.1.2. Operaciones de cuentas y préstamos

- **GET /consultar_cuenta?id=ID**
  - Consulta una cuenta por ID (con failover automático).
  - Ejemplo: `GET http://localhost:8080/consultar_cuenta?id=42`
- **POST /transferir_cuenta?origen=&destino=&monto=&txId=**
  - Realiza una transferencia entre cuentas (con failover inteligente).
  - Parámetros: `origen`, `destino`, `monto`, `txId` (idempotencia)
  - Ejemplo: `POST http://localhost:8080/transferir_cuenta?origen=42&destino=100&monto=50.00&txId=tx-001`
- **GET /consultar_transacciones?id=ID**
  - Consulta las transacciones de una cuenta (con failover automático).
  - Ejemplo: `GET http://localhost:8080/consultar_transacciones?id=42`
- **GET /estado_prestamo?id=ID**
  - Consulta el estado de préstamos de una cuenta (con failover automático).
  - Ejemplo: `GET http://localhost:8080/estado_prestamo?id=42`
- **POST /crear_prestamo?idCliente=&monto=&tasaAnual=&loanId=**
  - Crea un nuevo préstamo para un cliente (con failover inteligente).
  - Parámetros: `idCliente`, `monto`, `tasaAnual` (0-1, ej: 0.25 = 25%), `loanId` (único para idempotencia)
  - Ejemplo:
    `POST http://localhost:8080/crear_prestamo?idCliente=42&monto=1000.00&tasaAnual=0.25&loanId=loan-001`
  - Respuesta: `{"ok":true,"idPrestamo":1}` o `{"ok":false,"error":"CLIENTE_NO_EXISTE"}`

#### Mantenimiento y Verificación

- **POST /verify_replica?id=ID**
  - Verifica la consistencia de una cuenta entre todas sus réplicas y repara automáticamente si encuentra inconsistencias.
  - Parámetros: `id` (ID de la cuenta a verificar)
  - Proceso:
    1. Calcula la partición de la cuenta
    2. Obtiene todas las réplicas registradas para esa partición
    3. Verifica la consistencia de los datos entre réplicas usando `AccountVerifier`
    4. Si detecta inconsistencias, ejecuta reparación automática usando `AccountRepairer`
  - Ejemplo:
    `POST http://localhost:8080/verify_replica?id=42`
  - Respuestas:
    - **Consistente (sin reparación necesaria)**:

      ```json
      {
        "ok": true,
        "verify": {
          "consistent": true,
          "replicas_checked": 3,
          "primary_data": {"id":42,"saldo":"1000.00",...}
        }
      }
      ```

    - **Inconsistente (con reparación exitosa)**:

      ```json
      {
        "ok": true,
        "verify": {
          "consistent": false,
          "replicas_checked": 3,
          "inconsistencies": ["nodeB: saldo mismatch", "nodeC: missing record"]
        },
        "repair": {
          "success": true,
          "repaired_nodes": ["nodeB", "nodeC"],
          "source": "nodeA"
        }
      }
      ```

    - **Error (sin réplicas disponibles)**:

      ```json
      {
        "ok": false,
        "error": "NODOS_NO_REGISTRADOS",
        "msg": "No hay réplicas registradas para la partición 1",
        "partition": 1
      }
      ```

  - **Uso desde CLI**:

    ```powershell
    java BankCli.java verify-replica 42 --coordinator=localhost:8080
    ```

#### Monitoreo

- **GET /routing**
  - Devuelve el mapeo actual de particiones y nodos registrados.
  - Respuesta: `{"ok":true,"routing":{"0":[{"host":"localhost","port":9001,"priority":0},...]}}`

- **GET /healthz**
  - Verifica que el coordinador está activo.
  - Respuesta: `{"ok":true,"msg":"coordinator up"}`

- **GET /metrics**
  - Devuelve métricas del coordinador.
  - Respuesta: `{"ok":true,"metrics":{"req_total":100,"fallbacks_total":5,"errors_total":2}}`

### 7.2. Worker (Nodo)

#### 7.2.1. Operaciones principales

- **GET /consultar_cuenta?id=ID**
  - Devuelve la cuenta local si existe.
- **POST /transferir_cuenta?origen=&destino=&monto=&txId=**
  - Realiza una transferencia local (idempotente por txId).
- **GET /consultar_transacciones?id=ID**
  - Devuelve las transacciones de una cuenta local.
- **GET /estado_prestamo?id=ID**
  - Devuelve el estado de préstamos de una cuenta local.
- **POST /crear_prestamo?idCliente=&monto=&tasaAnual=&loanId=**
  - Crea un préstamo local para un cliente.

#### 7.2.2. Monitoreo y caos

- **GET /health** y **GET /healthz**
  - Verifica que el nodo está activo.
- **GET /metrics**
  - Devuelve métricas del nodo (requests, latencias, etc).
- **GET /chaos/latency?ms=NUM**
  - Inyecta latencia artificial en todas las operaciones (para pruebas de tolerancia a fallos).
  - Ejemplo: `GET http://localhost:9091/chaos/latency?ms=200`
- **GET /chaos/disk?on=true|false**
  - Simula fallo de disco (todas las escrituras fallan si está activado).
  - Ejemplo: `GET http://localhost:9091/chaos/disk?on=true`
- **GET /chaos/crash**
  - Simula un crash inmediato del proceso (mata el worker para pruebas de recuperación).

## 8. Estadísticas y benchmarks

### 8.1. Consultar métricas de los nodos

- Coordinador:

  ```powershell
  curl http://localhost:8080/metrics
  ```

- Worker (ejemplo para nodeA en 9091):

  ```powershell
  curl http://localhost:9091/metrics
  ```

### 8.2. Ejecutar benchmarks automáticos (3 fases: normal, fallo, recuperación)

Ejemplo de benchmark para cada operación usando loadgen:

**Consultas:**

```powershell
java -cp .\target\classes cc4p1.clients.cli.BankCli loadgen --ops=consultar --coordinator=127.0.0.1:8080 --accountRange=1:100 --threads=4 --total=1000
```

**Transferencias:**

```powershell
java -cp .\target\classes cc4p1.clients.cli.BankCli loadgen --ops=transfer --coordinator=127.0.0.1:8080 --accountRange=1:100 --min=1 --max=20 --threads=4 --total=1000
```

**Préstamos:**

```powershell
java -cp .\target\classes cc4p1.clients.cli.BankCli loadgen --ops=loan --coordinator=127.0.0.1:8080 --idClienteRange=1:100 --min=1 --max=20 --tasaAnual=0.25 --threads=4 --total=1000
```

Esto genera archivos CSV y JSON por fase: `bench_disk_normal.csv`, `bench_disk_fail.csv`, `bench_disk_recover.csv`, y sus resúmenes `bench_disk_normal.json`, etc.

## 9. Chat de cliente

Para acceder a las funcionalidades del chat de cliente, ejecuta ```ClientChatMain``` luego de ```CoordinatorServer``` y ```WorkerMain```.

Lo primero que se debe hacer es acceder a tu cuenta, para ello escribe en la caja de texto ```Acceder <Tu número de cuenta>```. Puedes usar cualquier número pero se sugieren los números ```3, 222, 2``` dada la base de datos generada con los archivos demo que generan estos datos.

El chat usa palabras clave así que basta con escribir cualquiera de estas palabras en el chat para obtener un resultado apropiado.

```
transaccion
transacciones
deudas
prestamos
saldo
```

Luego, para salir de tu cuenta, usa el comando ```Salir <Tu número de cuenta>``` y puedes volver a acceder a otra cuenta con el comando ```Acceder <Tu número de cuenta>```
