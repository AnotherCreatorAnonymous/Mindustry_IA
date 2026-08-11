import os
import sys
import time
from stable_baselines3 import DQN

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, BASE_DIR)

from Entorno_Mindustry import MindustryEnv

MODEL_PATH = os.path.abspath(os.path.join(BASE_DIR, "..", "IA_Entrenamiento", "modelos", "dron_rastreador_v1.zip"))

def evaluar_dron():
    print("🧠 Cargando el Cerebro Entrenado (Comandante Asignado)...")
    if not os.path.exists(MODEL_PATH):
        print(f"❌ Error: No se encontró el cerebro en '{MODEL_PATH}'.")
        return

    # Inicializamos el entorno
    env = MindustryEnv()

    # Cargamos el modelo
    modelo = DQN.load(MODEL_PATH)
    print("✅ Cerebro multi-mineral cargado exitosamente. Iniciando prueba de campo visual (Cero Aleatoriedad)...")

    # Evaluaremos al dron a lo largo de 5 episodios completos
    for episodio in range(1, 6):
        print(f"\n🎬 --- INICIANDO VUELO DE EVALUACIÓN {episodio} ---")
        obs, info = env.reset()

        terminado = False
        truncado = False
        pasos = 0
        recompensa_total = 0

        # Bucle gobernado estrictamente por las banderas de Gymnasium
        while not (terminado or truncado):
            # La IA predice el mejor movimiento usando 100% de lo aprendido (cero azar)
            accion, _states = modelo.predict(obs, deterministic=True)
            obs, reward, terminado, truncado, info = env.step(accion)

            recompensa_total += reward
            pasos += 1

            # Delay optimizado para una visualización fluida
            time.sleep(0.1)

        if terminado:
            # Texto actualizado: Ya no busca solo cobre, busca el mineral asignado
            print(f"🎉 ¡Misión Exitosa! El dron cazó el mineral objetivo en {pasos} pasos.")
            print(f"🏆 Recompensa acumulada en este vuelo: {recompensa_total:.2f}")
        elif truncado:
            print(f"⏱️ Límite de tiempo. El dron no halló el mineral objetivo tras {pasos} movimientos.")
            print(f"📉 Recompensa de consolación: {recompensa_total:.2f}")

    env.close()
    print("\n🏁 Evaluación de campo finalizada. El Comandante ha aterrizado.")

if __name__ == "__main__":
    evaluar_dron()