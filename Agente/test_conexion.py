import json
import socket
import time


HOST = "127.0.0.1"
PORT = 5050


def enviar_y_leer(sock, reader, payload):
    mensaje = json.dumps(payload) + "\n"
    sock.sendall(mensaje.encode("utf-8"))
    respuesta = reader.readline()
    if not respuesta:
        raise ConnectionError("El servidor cerró la conexión o no respondió con una línea completa.")

    respuesta = respuesta.strip()
    print("Respuesta cruda:", respuesta)
    try:
        print("Respuesta JSON:", json.loads(respuesta))
    except json.JSONDecodeError:
        print("La respuesta no es JSON válido.")


def main():
    with socket.create_connection((HOST, PORT), timeout=5) as sock:
        sock.settimeout(5)
        with sock.makefile("r", encoding="utf-8", newline="\n") as reader:
            print("Enviando reset...")
            enviar_y_leer(sock, reader, {"action": "reset", "x": 0, "y": 0})

            for i in range(5):
                print(f"Enviando move {i + 1}/5 hacia el este...")
                enviar_y_leer(sock, reader, {"action": "move", "x": 6, "y": 0})
                time.sleep(0.5)


if __name__ == "__main__":
    main()