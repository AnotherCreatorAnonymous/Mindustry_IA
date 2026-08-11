# mindutry-ia — Agent instructions

## Architecture

Two components communicating over TCP port 5050 (JSON lines):

| Component | Language | Dir | Purpose |
|-----------|----------|-----|---------|
| Mindustry mod | Java (Gradle) | `Agent_Mod/` | Mindustry mod (`ai-agent-bridge`). TCP server on `:5050`, exposes unit control + ore radar for 6 ores (copper, lead, coal, titanium, thorium, scrap). Entrypoint: `ai.AgentBridge`. |
| RL Agent | Python | `Agente/` | Gymnasium env + Stable-Baselines3 DQN training/eval client. Connects to `127.0.0.1:5050`. |

- `Server/` is empty (unused).
- `AgentBridge.java` at repo root is a stale/older copy; the live source is `Agent_Mod/src/ai/AgentBridge.java`.
- No Python dependency file (`requirements.txt`, `pyproject.toml`, etc.) — deps are: `gymnasium`, `numpy`, `stable-baselines3`.

## Build & run flow

### 1. Mindustry mod
- Compile with `Agent_Mod/build.gradle`. **Requires** local `Mindustry/desktop/build/libs/Mindustry.jar` (outside repo).
- Build output: `AgentBridge.jar` (auto-copied to `%APPDATA%/Mindustry/mods/` on Windows).
- Start Mindustry, load a map. The mod starts a TCP server on `:5050` when a world loads.

### 2. Python agent
- `Agente/test_conexion.py` — quick connectivity test.
- `Agente/Entrenar_Agente.py` — DQN training (1M timesteps, checkpoints every 50k). Expects model dir `../IA_Entrenamiento/modelos/`.
- `Agente/Probar_Cerebro.py` — evaluate a trained model for 5 episodes.
- `Agente/Entorno_Mindustry.py` — Gymnasium env (24-dim obs, 4 discrete actions N/S/E/W).

### Docker
- `Dockerfile` provides a Python 3.10 + Java 17 environment for development. Exposes port 6006 (TensorBoard).

## Protocol

TCP JSON lines, sent with `\n` delimiter:

| Action | Request fields | Behaviour |
|--------|---------------|-----------|
| `move` | `{"action": "move", "x": <int>, "y": <int>}` | Sets velocity target relative to current pos. Response contains full observation. |
| `reset` | `{"action": "reset"}` | Performs native respawn (`Vars.player.clearUnit()` + `checkSpawn()`). |
| `none` | `{"action": "none"}` | Read-only state observation. |

Observation format (one JSON line):
```json
{"drone_x": <float>, "drone_y": <float>, "<ore>_dst": <float>, "<ore>_x": <int>, "<ore>_y": <int>, ...}
```
Ores: copper, lead, coal, titanium, thorium, scrap. Missing ores set `_dst`, `_x`, `_y` to -1. Coordinates in tiles; distance in pixels.

## Known pitfalls

- The mod's TCP server binds `:5050` immediately — kill stale processes if port is taken.
- All game-state mutations **must** run on the game loop thread (`Core.app.post()` + `CountDownLatch`). Direct mutation from the TCP thread will be silently ignored.
- The root `AgentBridge.java` is the **old** version (manual tile scan, single copper ore). `Agent_Mod/src/ai/AgentBridge.java` is the current version with spatial index + 6-ore observations.
- No lint / test / typecheck scripts are configured.
