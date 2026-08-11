import os
import sys
from stable_baselines3 import DQN
from stable_baselines3.common.monitor import Monitor
from stable_baselines3.common.vec_env import DummyVecEnv
from stable_baselines3.common.callbacks import CheckpointCallback

# Importamos directamente la clase del entorno
from Entorno_Mindustry import MindustryEnv

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
LOG_DIR = os.path.join(BASE_DIR, "..", "IA_Entrenamiento", "logs")
MODEL_DIR = os.path.join(BASE_DIR, "..", "IA_Entrenamiento", "modelos")
CHECKPOINT_DIR = os.path.join(MODEL_DIR, "checkpoints")

os.makedirs(CHECKPOINT_DIR, exist_ok=True)

def entrenar_dron():
    print("🔌 Conectando la Red Neuronal al Entorno de Mindustry...")

    # 🟢 SOLUCIÓN: Instanciación limpia del entorno para evitar capturas por referencia
    env = DummyVecEnv([lambda: Monitor(MindustryEnv())])

    checkpoint_callback = CheckpointCallback(
        save_freq=50000,
        save_path=CHECKPOINT_DIR,
        name_prefix="dron_v1"
    )

    modelo_path = os.path.join(MODEL_DIR, "dron_rastreador_v1.zip")
    lr_deseado = 0.0005

    if os.path.exists(modelo_path):
        print("🧠 Modelo anterior detectado. Retomando el entrenamiento...")
        # Ajustes de memoria/exploración para mitigar colapso de política y olvido catastrófico
        modelo = DQN.load(
            modelo_path,
            env=env,
            tensorboard_log=LOG_DIR,
            verbose=1,
            custom_objects={
                "learning_rate": lr_deseado,
                "buffer_size": 100000,
                "exploration_fraction": 0.6,
                "exploration_initial_eps": 0.6,
                "exploration_final_eps": 0.05,
                "learning_starts": 10000,
            }
        )
    else:
        print("🌱 Creando una nueva red neuronal desde cero...")
        # Ajustes de memoria/exploración para mitigar colapso de política y olvido catastrófico
        modelo = DQN(
            "MlpPolicy",
            env,
            verbose=1,
            tensorboard_log=LOG_DIR,
            learning_rate=lr_deseado,
            buffer_size=100000,
            exploration_fraction=0.6,
            exploration_initial_eps=0.6,
            exploration_final_eps=0.05,
            learning_starts=10000,
        )

    pasos_totales = 1_000_000
    print(f"🚀 Iniciando adiestramiento masivo. El dron intentará {pasos_totales} movimientos...")

    try:
        # Lanzar Entrenamiento
        modelo.learn(
            total_timesteps=pasos_totales,
            callback=checkpoint_callback,
            reset_num_timesteps=False,
            progress_bar=True
        )
        # Guardado exitoso si llega al millón completo
        modelo.save(modelo_path)
        print(f"✅ ¡Entrenamiento completo! Cerebro principal guardado en: {modelo_path}")

    except KeyboardInterrupt:
        # 🔴 SOLUCIÓN: El escudo definitivo ante cierres inesperados
        print("\n🛑 Entrenamiento pausado manualmente por el usuario (Ctrl+C).")
        print("💾 Salvando el estado actual del cerebro artificial en el disco duro...")
        modelo.save(modelo_path)
        print(f"✅ ¡Progreso a salvo! Modelo guardado de emergencia en: {modelo_path}")
        try:
            env.close()
        except:
            pass
        sys.exit(0)

if __name__ == "__main__":
    modelo = entrenar_dron()