import socket
import json
import time
import random

def ejecutar_entorno_rl():
    host = '127.0.0.1'
    puerto = 5050

    print("🤖 Iniciando Entorno de Aprendizaje por Refuerzo...")

    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.connect((host, puerto))

            # --- ESTADO INICIAL (Reset) ---
            # Pedimos la telemetría sin movernos para saber dónde estamos
            s.sendall((json.dumps({"action": "none", "x": 0, "y": 0}) + '\n').encode('utf-8'))
            estado_crudo = s.recv(1024).decode('utf-8').strip()
            estado = json.loads(estado_crudo)

            distancia_anterior = estado.get("distancia", 100)
            print(f"📍 Posición Inicial -> Distancia al cobre: {distancia_anterior:.2f} baldosas\n")

            # --- BUCLE DE ENTRENAMIENTO (Episodio de 15 pasos) ---
            for step in range(1, 16):

                # 1. LA IA DECIDE LA ACCIÓN (Por ahora, elige un botón al azar)
                # 0=Norte, 1=Sur, 2=Este, 3=Oeste
                accion_elegida = random.randint(0, 3)

                # El "Wrapper" traduce el botón a coordenadas que Java pueda entender
                offset_x, offset_y = 0, 0
                if accion_elegida == 0: offset_y = 6      # Norte
                elif accion_elegida == 1: offset_y = -6   # Sur
                elif accion_elegida == 2: offset_x = 6    # Este
                elif accion_elegida == 3: offset_x = -6   # Oeste

                direcciones = ["Norte", "Sur", "Este", "Oeste"]
                print(f"🧠 Step {step} | Acción IA: Mover al {direcciones[accion_elegida]}")

                # 2. ENVIAR ACCIÓN AL ENTORNO (Java)
                comando = json.dumps({"action": "move", "x": offset_x, "y": offset_y})
                s.sendall((comando + '\n').encode('utf-8'))

                # Le damos al piloto automático 1.5 segundos para realizar el vuelo físico
                time.sleep(1.5)

                # 3. OBSERVAR NUEVO ESTADO Y CALCULAR RECOMPENSA
                estado_crudo = s.recv(1024).decode('utf-8').strip()
                estado_nuevo = json.loads(estado_crudo)
                distancia_actual = estado_nuevo.get("distancia", 100)

                # LA MATEMÁTICA DEL APRENDIZAJE:
                # Recompensa positiva (+) si se acerca, negativa (-) si se aleja.
                recompensa = distancia_anterior - distancia_actual

                print(f"🌍 Entorno | Nueva Distancia: {distancia_actual:.2f} | 🎁 Recompensa: {recompensa:+.2f}\n")

                # 4. CONDICIÓN DE VICTORIA
                if distancia_actual < 3.0 and distancia_actual != -1:
                    print("🎉 ¡OBJETIVO ALCANZADO! El dron navegó con éxito hasta el cobre.")
                    break

                # Guardamos la distancia en memoria para calcular el siguiente paso
                distancia_anterior = distancia_actual

    except Exception as e:
        print(f"⚠️ Error de conexión: {e}")

if __name__ == "__main__":
    ejecutar_entorno_rl()