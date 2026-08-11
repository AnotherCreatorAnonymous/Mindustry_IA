import gymnasium as gym
from gymnasium import spaces
import numpy as np
import socket
import json
import time

class MindustryEnv(gym.Env):
    def __init__(self):
        super().__init__()
        self.action_space = spaces.Discrete(4)
        self.minerales = ["copper", "lead", "coal", "titanium", "thorium", "scrap"]
        self.objetivo_actual = 0
        self.estado_minerales = [0.0] * len(self.minerales)
        self.minerales_visitados = []

        self.observation_space = spaces.Box(
            low=-2.0,
            high=2.0,
            shape=(24,),
            dtype=np.float32
        )

        self.host = '127.0.0.1'
        self.port = 5050
        self.socket = None
        self.flujo_lectura = None # Buffer de línea profesional

        self.distancia_anterior = 100.0
        self.pasos_actuales = 0
        self.max_pasos = 200

    def _conectar(self):
        """Conecta o reconecta al servidor Java con esquemas de seguridad."""
        intentos = 0
        while self.socket is None:
            try:
                self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                self.socket.settimeout(5.0) # Bajamos a 5 segundos para que reaccione más rápido
                self.socket.connect((self.host, self.port))
                print("✅ Conexión TCP de Gymnasium establecida de forma segura.")
            except (socket.error, socket.timeout):
                intentos += 1
                print(f"⚠️ Servidor no disponible. Intento de reconexión {intentos} en 3 segundos...")
                self.close()
                time.sleep(3)

    def _recibir_estado(self):
        """Lee estados, procesa los 6 minerales y codifica el objetivo actual."""
        try:
            datos_crudos = self.socket.recv(4096).decode('utf-8').strip()
            if not datos_crudos:
                raise socket.error("Conexión vacía.")

            if "\n" in datos_crudos:
                datos_crudos = datos_crudos.split("\n")[0]

            estado = json.loads(datos_crudos)

            # 1. Posición del dron (Viene en Píxeles nativos de Java)
            drone_x = estado.get("drone_x", 0.0)
            drone_y = estado.get("drone_y", 0.0)

            obs = []

            distancia_objetivo_real = 1000.0

            # 2. Procesamos cada mineral dinámicamente
            for indice, mineral in enumerate(self.minerales):
                dst = estado.get(f"{mineral}_dst", -1.0)
                mx = estado.get(f"{mineral}_x", -1.0)
                my = estado.get(f"{mineral}_y", -1.0)

                if dst != -1.0 and mx != -1.0:
                    # TRADUCCIÓN CRÍTICA: Convertimos la veta de Tiles a Píxeles
                    ore_x = mx * 8.0
                    ore_y = my * 8.0

                    # Recalculamos la distancia real matemática en píxeles
                    delta_x = ore_x - drone_x
                    delta_y = ore_y - drone_y
                    dist_real = float(np.sqrt(delta_x**2 + delta_y**2))

                    if indice == self.objetivo_actual:
                        distancia_objetivo_real = dist_real

                    # Filtro de seguridad y normalización (tus mismos parámetros)
                    delta_x_norm = np.clip(delta_x, -1000.0, 1000.0) / 500.0
                    delta_y_norm = np.clip(delta_y, -1000.0, 1000.0) / 500.0
                    dist_norm = np.clip(dist_real, 0.0, 1000.0) / 500.0

                    obs.extend([dist_norm, delta_x_norm, delta_y_norm])
                else:
                    # Si el mineral no existe en el mapa, inyectamos señal muerta (-1.0)
                    obs.extend([-1.0, -1.0, -1.0])

            obs.extend(self.estado_minerales)

            return np.array(obs, dtype=np.float32), distancia_objetivo_real

        except (socket.timeout, socket.error, json.JSONDecodeError) as e:
            print(f"💥 Error de red o JSON corrupto. Forzando reconexión...")
            self.close()
            self._conectar()
            # Devolvemos un array de 24 posiciones con error y la dist máxima
            return np.full(24, -1.0, dtype=np.float32), 1000.0


    def reset(self, seed=None, options=None):
        super().reset(seed=seed)
        self._conectar()
        self.pasos_actuales = 0
        self.minerales_visitados = []
        self.objetivo_actual = int(self.np_random.integers(0, len(self.minerales)))
        self.estado_minerales = [0.0] * len(self.minerales)
        self.estado_minerales[self.objetivo_actual] = 1.0
        print(f"🎯 NUEVO EPISODIO - Objetivo asignado: {self.minerales[self.objetivo_actual]}")

        try:
            comando = json.dumps({"action": "reset", "x": 0, "y": 0})
            self.socket.sendall((comando + '\n').encode('utf-8'))
        except socket.error:
            self.close()
            self._conectar()

        observacion_normalizada, distancia_objetivo_real = self._recibir_estado()
        self.distancia_anterior = distancia_objetivo_real

        return observacion_normalizada, {"mensaje": "Entorno listo"}

    def step(self, action):
        self.pasos_actuales += 1

        offset_x, offset_y = 0, 0
        if action == 0: offset_y = 6     # Norte
        elif action == 1: offset_y = -6  # Sur
        elif action == 2: offset_x = 6   # Este
        elif action == 3: offset_x = -6  # Oeste

        try:
            comando = json.dumps({"action": "move", "x": offset_x, "y": offset_y})
            self.socket.sendall((comando + '\n').encode('utf-8'))
        except socket.error:
            print("⚠️ Error al enviar comando. Intentando recuperar...")
            self.close()
            self._conectar()

        time.sleep(0.1)
        observacion_normalizada, distancia_objetivo_real = self._recibir_estado()

        reward = -1.0
        terminated = False

        if distancia_objetivo_real != -1 and distancia_objetivo_real < 20.0:
            reward += 1000.0
            mineral_actual = self.minerales[self.objetivo_actual]
            if mineral_actual not in self.minerales_visitados:
                self.minerales_visitados.append(mineral_actual)

            disponibles = [
                indice for indice, mineral in enumerate(self.minerales)
                if mineral not in self.minerales_visitados and indice != self.objetivo_actual
            ]

            if disponibles:
                self.objetivo_actual = int(self.np_random.choice(disponibles))
                self.estado_minerales = [0.0] * len(self.minerales)
                self.estado_minerales[self.objetivo_actual] = 1.0

            observacion_normalizada, distancia_objetivo_real = self._recibir_estado()

        self.distancia_anterior = distancia_objetivo_real
        truncated = bool(self.pasos_actuales >= self.max_pasos)

        return observacion_normalizada, reward, terminated, truncated, {"distancia_restante": distancia_objetivo_real, "objetivo": self.minerales[self.objetivo_actual]}

    def close(self):
        if self.flujo_lectura:
            try: self.flujo_lectura.close()
            except: pass
            self.flujo_lectura = None
        if self.socket:
            try: self.socket.close()
            except: pass
            self.socket = None