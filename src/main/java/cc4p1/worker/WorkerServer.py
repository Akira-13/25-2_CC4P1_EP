import os
import csv
import json
import uuid
import threading
import time
import datetime
from decimal import Decimal
from flask import Flask, request, jsonify

# ------------------------- CONFIG -------------------------
DATA_DIR   = os.environ.get("DATA_DIR", "data/node_py")
NUM_PARTS  = int(os.environ.get("PARTS", "3"))
COORD_URL  = os.environ.get("COORD", "http://127.0.0.1:8080")  # <- coordinador
PART_LIST  = os.environ.get("PARTITIONS", "0,1,2")
NODE_ID    = os.environ.get("NODE_ID", "nodePY")
HOST       = os.environ.get("HOST", "127.0.0.1")
PORT       = int(os.environ.get("PORT", "9092"))
REGISTER_INTERVAL_SEC = int(os.environ.get("REGISTER_EVERY", "30"))

# ------------------------- APP ----------------------------
app = Flask(__name__)
LOCKS = {}
os.makedirs(os.path.join(DATA_DIR, "partitions"), exist_ok=True)

def lock_for(account_id):
    if account_id not in LOCKS:
        LOCKS[account_id] = threading.Lock()
    return LOCKS[account_id]

def part_for_id(id_num): return int(id_num) % NUM_PARTS
def cuentas_file(p):     return os.path.join(DATA_DIR, "partitions", f"cuentas_p{p}.csv")
def trans_file(p):       return os.path.join(DATA_DIR, "partitions", f"transacciones_p{p}.csv")
def prestamos_file(p):   return os.path.join(DATA_DIR, "partitions", f"prestamos_p{p}.csv")
def pagos_file(p):       return os.path.join(DATA_DIR, "partitions", f"pagos_p{p}.csv")

def ensure_file_with_header(path, header):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if not os.path.exists(path):
        with open(path, "w", newline="", encoding="utf-8") as f:
            f.write(header + "\n")

def get_account(id_cuenta):
    p = part_for_id(id_cuenta)
    path = cuentas_file(p)
    if not os.path.exists(path): return None
    with open(path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f, delimiter=";")
        for row in reader:
            if int(row["id_cuenta"]) == id_cuenta:
                return {
                    "id": id_cuenta,
                    "id_cliente": int(row["id_cliente"]),
                    "saldo": Decimal(row["saldo"]),
                    "fecha_apertura": row["fecha_apertura"]
                }
    return None

def update_account(account):
    p = part_for_id(account["id"])
    path = cuentas_file(p)
    ensure_file_with_header(path, "id_cuenta;id_cliente;saldo;fecha_apertura")
    rows = []
    if os.path.exists(path):
        with open(path, newline="", encoding="utf-8") as f:
            reader = csv.reader(f, delimiter=";")
            next(reader, None)
            for r in reader:
                if r and int(r[0]) != account["id"]:
                    rows.append(r)
    rows.append([account["id"], account["id_cliente"], str(account["saldo"]), account["fecha_apertura"]])
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f, delimiter=";")
        writer.writerow(["id_cuenta", "id_cliente", "saldo", "fecha_apertura"])
        writer.writerows(rows)

def append_transaction(tx):
    p = part_for_id(tx["id_cuenta"])
    path = trans_file(p)
    ensure_file_with_header(path, "id_tx;id_cuenta;tipo;monto;fecha")
    with open(path, "a", newline="", encoding="utf-8") as f:
        writer = csv.writer(f, delimiter=";")
        writer.writerow([tx["id_tx"], tx["id_cuenta"], tx["tipo"], tx["monto"], tx["fecha"]])

# ------------------------- ENDPOINTS -----------------------
@app.route("/health")
def health():
    return jsonify({"ok": True})

@app.route("/consultar_cuenta")
def consultar_cuenta():
    try:
        id_str = request.args.get("id")
        if not id_str:
            return jsonify(ok=False, error="MISSING_ID"), 400
        id_cuenta = int(id_str)
        acc = get_account(id_cuenta)
        if not acc:
            return jsonify(ok=False, error="NOT_FOUND"), 404
        return jsonify(ok=True, account=acc)
    except Exception as e:
        return jsonify(ok=False, error=str(e)), 500

@app.route("/transferir", methods=["POST"])
def transferir():
    data = request.get_json(silent=True) or request.form or request.args
    try:
        from_id = int(data.get("from") or data.get("origen"))
        to_id   = int(data.get("to")   or data.get("destino"))
        monto   = Decimal(str(data.get("monto")))
        tx_id   = data.get("txId") or str(uuid.uuid4())
    except Exception:
        return jsonify(ok=False, error="BAD_REQUEST"), 400
    if from_id == to_id:
        return jsonify(ok=False, error="SAME_ACCOUNT"), 400

    a_lock = lock_for(min(from_id, to_id))
    b_lock = lock_for(max(from_id, to_id))
    a_lock.acquire(); b_lock.acquire()
    try:
        acc_from = get_account(from_id)
        acc_to   = get_account(to_id)
        if not acc_from or not acc_to:
            return jsonify(ok=False, error="NOT_FOUND"), 404
        if acc_from["saldo"] < monto:
            return jsonify(ok=False, error="INSUFFICIENT_FUNDS"), 409

        # update balances
        acc_from["saldo"] -= monto
        acc_to["saldo"]   += monto
        update_account(acc_from)
        update_account(acc_to)

        fecha = datetime.date.today().isoformat()
        # asientos simétricos con mismo tx_id
        append_transaction({"id_tx": tx_id, "id_cuenta": from_id, "tipo": "DEBITO",  "monto": str(monto), "fecha": fecha})
        append_transaction({"id_tx": tx_id, "id_cuenta": to_id,   "tipo": "CREDITO", "monto": str(monto), "fecha": fecha})

        return jsonify(ok=True, txId=tx_id, from_id=from_id, to_id=to_id, monto=str(monto))
    finally:
        b_lock.release(); a_lock.release()

@app.route("/consultar_transacciones")
def consultar_transacciones():
    id_str = request.args.get("id")
    if not id_str:
        return jsonify(ok=False, error="MISSING_ID"), 400
    id_cuenta = int(id_str)
    p = part_for_id(id_cuenta)
    path = trans_file(p)
    if not os.path.exists(path):
        return jsonify(ok=True, cuenta=id_cuenta, transacciones=[])
    result = []
    with open(path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f, delimiter=";")
        for row in reader:
            if int(row["id_cuenta"]) == id_cuenta:
                result.append(row)
    return jsonify(ok=True, cuenta=id_cuenta, transacciones=result)

@app.route("/crear_prestamo", methods=["POST"])
def crear_prestamo():
    data = request.get_json(silent=True) or request.form or request.args
    try:
        # Aceptamos cuentaId o idCliente (para mantener compatibilidad)
        cuenta_id = int(data.get("cuentaId") or data.get("idCliente"))
        monto = Decimal(str(data.get("monto")))
        tasa  = Decimal(str(data.get("tasa") or "0"))
    except Exception:
        return jsonify(ok=False, error="BAD_REQUEST"), 400
    p = part_for_id(cuenta_id)
    path = prestamos_file(p)
    ensure_file_with_header(path, "id_prestamo;id_cliente;monto;tasa_anual;fecha;pendiente;estado")
    prestamo_id = int(time.time() * 1000)
    fecha = datetime.date.today().isoformat()
    pendiente = monto
    estado = "ACTIVO"
    with open(path, "a", newline="", encoding="utf-8") as f:
        writer = csv.writer(f, delimiter=";")
        writer.writerow([prestamo_id, cuenta_id, str(monto), str(tasa), fecha, str(pendiente), estado])
    return jsonify(ok=True, loanId=prestamo_id, cliente=cuenta_id, monto=str(monto), tasa=str(tasa))

@app.route("/prestamo_estado")
def prestamo_estado():
    id_str = request.args.get("id")
    if not id_str:
        return jsonify(ok=False, error="MISSING_ID"), 400
    id_cliente = int(id_str)
    p = part_for_id(id_cliente)
    path = prestamos_file(p)
    if not os.path.exists(path):
        return jsonify(ok=True, cliente=id_cliente, prestamos=[])
    prestamos = []
    with open(path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f, delimiter=";")
        for row in reader:
            if int(row["id_cliente"]) == id_cliente:
                prestamos.append(row)
    return jsonify(ok=True, cliente=id_cliente, prestamos=prestamos)

# --------------------- AUTO-REGISTRO -----------------------
def register_once():
    """
    POST /register?host=...&port=...&role=replica&partitions=0,1,2
    Sin dependencias externas (urllib).
    """
    import urllib.parse, urllib.request
    try:
        url = f"{COORD_URL}/register?host={urllib.parse.quote(HOST)}&port={PORT}&role=replica&partitions={urllib.parse.quote(PART_LIST)}"
        req = urllib.request.Request(url, method="POST")
        with urllib.request.urlopen(req, timeout=3) as resp:
            body = resp.read().decode("utf-8", errors="ignore")
            print(f"[{NODE_ID}] register -> {resp.status} {body}")
    except Exception as e:
        print(f"[{NODE_ID}] WARN: no pude registrar en coordinador: {e}")

def register_loop():
    # intento inmediato y luego cada REGISTER_INTERVAL_SEC con un ligero jitter
    register_once()
    while True:
        time.sleep(REGISTER_INTERVAL_SEC + (hash(NODE_ID) % 5))  # pequeño jitter 0..4s
        register_once()

# ------------------------- MAIN ---------------------------
if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default=HOST)
    parser.add_argument("--port", type=int, default=PORT)
    parser.add_argument("--nodeId", default=NODE_ID)
    args = parser.parse_args()

    # arrancar loop de registro en background
    threading.Thread(target=register_loop, daemon=True).start()

    print(f"[Worker {args.nodeId}] listening on {args.host}:{args.port}  parts={PART_LIST}  coord={COORD_URL}")
    app.run(host=args.host, port=args.port)
