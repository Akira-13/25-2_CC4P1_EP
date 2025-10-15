# README

## Generar datos

- Headers de cuentas: id_cuenta;id_cliente;saldo;fecha_apertura

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


# Pagos y Préstamos

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

#### Administración
- **POST /register?host=&port=&partitions=&role=**
  - Registra un nodo worker en la tabla de ruteo.
  - Parámetros: `host`, `port`, `partitions` (separadas por coma), `role` (primary/replica)
  - Ejemplo:
    `POST http://localhost:8080/register?host=localhost&port=9001&partitions=0,1,2&role=primary`

#### Operaciones de Cuentas
- **GET /consultar_cuenta?id=ID**
  - Consulta una cuenta por ID (con failover automático).
  - Ejemplo:
    `GET http://localhost:8080/consultar_cuenta?id=42`
  - Respuesta: `{"ok":true,"account":{"id":42,"idCliente":42,"saldo":"1000","fechaApertura":"2025-10-13"}}`

- **POST /transferir_cuenta?origen=&destino=&monto=&txId=**
  - Realiza una transferencia entre cuentas (con failover inteligente).
  - Parámetros: `origen` (ID cuenta origen), `destino` (ID cuenta destino), `monto`, `txId` (único para idempotencia)
  - Ejemplo:
    `POST http://localhost:8080/transferir_cuenta?origen=42&destino=100&monto=50.00&txId=tx-001`
  - Respuesta: `{"ok":true}` o `{"ok":false,"error":"SALDO_INSUFICIENTE"}`

- **GET /consultar_transacciones?id=ID**
  - Consulta las transacciones de una cuenta (con failover automático).
  - Ejemplo:
    `GET http://localhost:8080/consultar_transacciones?id=42`
  - Respuesta: `{"ok":true,"transacciones":[{"idTx":"tx-001","idCuenta":42,"tipo":"DEBITO","monto":"50.00","fecha":"2025-10-15"}]}`

#### Operaciones de Préstamos
- **GET /estado_prestamo?id=ID**
  - Consulta el estado de préstamos de una cuenta (con failover automático).
  - Ejemplo:
    `GET http://localhost:8080/estado_prestamo?id=42`
  - Respuesta: `{"ok":true,"prestamos":[{"idPrestamo":1,"monto":"1000.00","pendiente":"500.00","estado":"ACTIVO"}]}`

- **POST /crear_prestamo?idCliente=&monto=&tasaAnual=&loanId=**
  - Crea un nuevo préstamo para un cliente (con failover inteligente).
  - Parámetros: `idCliente`, `monto`, `tasaAnual` (0-1, ej: 0.25 = 25%), `loanId` (único para idempotencia)
  - Ejemplo:
    `POST http://localhost:8080/crear_prestamo?idCliente=42&monto=1000.00&tasaAnual=0.25&loanId=loan-001`
  - Respuesta: `{"ok":true,"idPrestamo":1}` o `{"ok":false,"error":"CLIENTE_NO_EXISTE"}`

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
