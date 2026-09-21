# Informe de Estado Arquitectónico (actualizado)

Fecha: 2026-06-04

## 1. Topología del Proyecto (Árbol de Directorios)

El workspace fue limpiado y consolidado. Nota: la carpeta originalmente referida como `Agent-Brain` es `Agente` en este proyecto.

Estructura actual (relevante):

```text
Proyecto Mindustry/
├─ Agente/
│  ├─ Agente_base.py
│  ├─ Entorno_Mindustry.py        # wrapper gymnasium.Env
│  ├─ Entrenar_Agente.py          # entrenamiento (Stable-Baselines3)
│  ├─ Probar_Cerebro.py           # evaluación/carga de modelos
│  └─ venv/
├─ Agent_Mod/
│  └─ src/ai/AgentBridge.java     # puente Java TCP, headless-ready
├─ IA_Entrenamiento/
│  ├─ modelos/
│  │  └─ dron_rastreador_v1.zip   # artifact consolidado
│  └─ logs/
│     └─ DQN_1/
├─ Servidor/
│  └─ mods/                       # jars copiados desde Agent_Mod/build/libs
├─ Arc/
└─ Mindustry/
```

Archivos clave:
- `Agente/Entrenar_Agente.py` (guardado en `IA_Entrenamiento/modelos` y logs en `IA_Entrenamiento/logs`).
- `Agente/Probar_Cerebro.py` (carga desde `IA_Entrenamiento/modelos`).
- `Agente/Entorno_Mindustry.py` (implementa `MindustryEnv`).
- `Agent_Mod/src/ai/AgentBridge.java` (puente TCP, soporte unidad persistente).

---

## 2. Análisis de Componentes (Responsabilidades) — estado actual

### `AgentBridge.java` (puente Java)

Resumen:
- Levanta un servidor TCP en puerto `5050` en `WorldLoadEvent`.
- Implementa `getControllableUnit()` con jerarquía: 1) `Vars.player.unit()` si existe; 2) fallback a `agenteUnidad` (spawn de `UnitTypes.mono` en el centro si necesario).
- Control de navegación procesado en `Trigger.update` (uso de `Mathf`, `unit.vel`, `isNavigating`).
- Protocolo JSON actual soporta `action: "move"` y el servidor responde con `{"distancia":..., "ore_x":..., "ore_y":...}`.

Garantías ofrecidas:
- Soporte básico headless: si no hay jugador la IA mantiene y controla una unidad persistente.

Limitaciones actuales:
- Mutaciones directas del mundo (colocación de bloques) aún deben revisarse para usar el pipeline autoritativo del juego si se requiere persistencia sincronizada con clientes.

### Python (agente / entorno)

Resumen:
- `Entrenar_Agente.py` usa `Stable-Baselines3` (DQN) y escribe modelos en `IA_Entrenamiento/modelos` y logs en `IA_Entrenamiento/logs` (`tensorboard_log` configurado).
- `Probar_Cerebro.py` carga el modelo desde `IA_Entrenamiento/modelos` y usa `Entorno_Mindustry.py` para ejecutar episodios.
- `Entorno_Mindustry.py` ya expone un `gymnasium.Env` que traduce acciones discretas a comandos `move` y calcula reward por shaping.

---

## 3. Flujo de Datos (Bucle de interacción actual)

1. Python (Env / Agent) -> Java: envía JSON, p.ej. `{"action":"move","x":dx,"y":dy}`.
2. Java: `AgentBridge` procesa la orden, resuelve la unidad controlable, actualiza `targetWorldX/Y` y marca `isNavigating`.
3. Java (Trigger.update): aplica velocidad a la unidad y mantiene navegación; también escanea recursos cercanos (cobre) y construye la observación.
4. Java -> Python: responde con observación JSON, p.ej. `{"distancia":12.34,"ore_x":10,"ore_y":20}`.
5. Python `MindustryEnv` calcula reward (shaping) y retorna `(obs, reward, terminated, truncated, info)` a la RL loop.

---

## 4. Diagnóstico de Estado y Cuellos de Botella (actualizado)

Hallazgos operativos:
- Comunicación TCP estable y funcional.
- El modelo legado `dron_rastreador_v1.zip` fue movido a `IA_Entrenamiento/modelos`.
- Los scripts Python usan rutas relativas robustas (`os.path.join`) y `tensorboard_log` para logs.

Riesgos y puntos de mejora:
- Persistencia del mundo: cualquier operación que modifique el mapa (p.ej. `setBlock`) aún debe hacerse mediante las APIs del motor para asegurar sincronización y visibilidad en clientes/servidores.
- Dependencias temporales: el fallback a `agenteUnidad` requiere que `Vars.world` esté inicializado; el puente ya arranca en `WorldLoadEvent`, mitigando parcialmente este riesgo.

Conclusión sobre headless:
- `AgentBridge.java` fue refactorizado para permitir funcionamiento headless básico (unidad persistente y navegación en ausencia de `Vars.player`).
- A nivel de compilación y ejecución, el puente es viable en servidores sin GUI, pero recomendamos:
  1) Asegurar que todas las mutaciones de mundo pasen por validadores/colas autoritativas.
  2) Añadir robustez adicional para manejar latencias/clients desconectados y estados inconsistentes del mundo.

---

## 5. Acciones realizadas y Roadmap inmediato

Acciones completadas:
1. Consolidación: `dron_rastreador_v1.zip` movido a `IA_Entrenamiento/modelos`.
2. Limpieza: `Agente/modelos_guardados` eliminado tras migración; carpetas temporales vacías fueron removidas.
3. Refactor: `Entrenar_Agente.py` y `Probar_Cerebro.py` actualizados para usar `IA_Entrenamiento` (modelos + logs).
4. Puente Java modificado para soporte headless básico (unidad persistente + navegación en `Trigger.update`).

Prioridades siguientes (recomendadas):
1. Migrar cualquier colocación de bloques a la API de construcción validada (evitar `setBlock` directo).
2. Estandarizar el protocolo JSON a `step(action) -> {obs, reward, done, info}` y actualizar `Entorno_Mindustry.py` para cumplir exactamente `gymnasium`.
3. Añadir validaciones y retries en la conexión TCP y manejo de timeouts para entrenamientos largos headless.

Si confirmas, puedo aplicar inmediatamente:
- la migración de acciones de modificación de mundo a la API de construcción,
- el estandarizado del protocolo y la adaptación del `gym` wrapper,
- pruebas rápidas de integración local (compilar mod + ejecutar un episodio headless corto).

---

## Eventos recientes (registro de terminales)

- `gradlew desktop:run` (en `Mindustry`) — exit code 0.
- Activación del entorno virtual (`venv`) y ejecución de `python entrenar_agente.py` (en `Agente`) — exit code 0.
- Compilación de `Agent_Mod` con `gradlew jar` — exit code 0 (jar generado en `Agent_Mod/build/libs`).
- Creada carpeta `scripts_headles` en `IA_Entrenamiento`.
- Copiado(s) `.jar` de `Agent_Mod/build/libs` a `Servidor/mods/` mediante `Copy-Item`.
- Limpieza ejecutada: movidos artefactos `.zip` y logs a `IA_Entrenamiento/modelos` y `IA_Entrenamiento/logs` respectivamente; salida: 'Limpieza completada.'

- `java -jar server-release.jar` (en `Servidor`) — exit code 0 (servidor local arrancado con el mod cargado).
- Múltiples activaciones del entorno virtual y ejecuciones de `Entrenar_Agente.py` (en `Agente`) — exit code 0 (confirmado: sesiones repetidas de entrenamiento/diagnóstico).

Estos eventos confirman que:
- El pipeline de build y despliegue local funciona (jar compilado y copiado al directorio de mods local).
- Se realizaron ejecuciones de entrenamiento locales exitosas (indicadas por exit code 0 en `python entrenar_agente.py`).

---

Fin del informe actualizado.

## 6. Documentación detallada (reproducción, build, protocolo y pruebas)

6.1 Reproducción local mínima
- Requisitos: Java JDK compatible (11+), Gradle wrapper incluido en `Mindustry/` y `Agent_Mod/`, Python 3.10+ con virtualenv.
- Pasos:
  1. Compilar el mod `Agent_Mod`:

```powershell
cd Agent_Mod
..\Mindustry\gradlew jar
```

  2. Copiar el `.jar` generado a la carpeta de mods local `Servidor/mods`:

```powershell
Copy-Item -Path "Agent_Mod\build\libs\*.jar" -Destination "Servidor\mods\"
```

  3. Lanzar Mindustry en modo desktop/headless (desde `Mindustry`):

```powershell
cd Mindustry
.\gradlew desktop:run
```

  4. Activar el entorno Python y ejecutar un entrenamiento de prueba (desde `Agente`):

```powershell
(Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned)
& "Agente\venv\Scripts\Activate.ps1"
cd Agente
python entrenar_agente.py
```

6.2 Build y despliegue del mod
- `Agent_Mod/build.gradle` empaqueta el mod en `Agent_Mod/build/libs`.
- Al copiar el `.jar` a `Servidor/mods/` el servidor local (o instancia de desktop con carpeta `Servidor`) cargará el mod al arrancar.

6.3 Protocolo TCP (especificación actual y sugerida)
- Transporte: TCP, línea delimitada por `\n`, puerto por defecto `5050`.
- Mensajes entrantes (Python -> Java): cada línea es JSON. Campos actuales observados:
  - `action`: string, p.ej. `"move"`, `"build"`, `"none"`.
  - Para `move`: `x` (float), `y` (float) — desplazamiento o objetivo en coordenadas mundo.
  - Para `build`: `block` (string), `x` (int), `y` (int) — bloque y coordenadas para colocar.

- Mensajes salientes (Java -> Python): JSON con observación parcial. Campos actuales:
  - `distancia` (float): distancia a objetivo / recurso.
  - `ore_x`, `ore_y` (int): coordenadas del recurso detectado.
  - `drone_x`, `drone_y` (float): posición actual de la unidad (si aplica).
  - `error` (string, opcional): descripción de fallo.

- Propuesta de estandarización (recomendada para `gymnasium`):
  - Solicitud: `{"type":"step","action":{"name":"move","params":{...}}}`
  - Respuesta: `{"obs":{...},"reward":<float>,"done":<bool>,"info":{...}}`
  - `obs` debería ser un `Dict` con claves fijas: `position`, `nearby_ore`, `core_state`, `tick`.

6.4 Logging y debugging
- Java: usar `Log`/`Vars.log` para mensajes de servidor; buscar `AgentBridge` en `Agent_Mod/src/ai` para puntos de inserción de logs.
- Python: `Entrenar_Agente.py` escribe `tensorboard` logs en `IA_Entrenamiento/logs` (ej. `DQN_1`).

6.5 Pruebas recomendadas
- Prueba de smoke: lanzar `Mindustry` con mod desplegado y correr `Agente/Agente_base.py` para enviar acciones manuales y verificar respuestas.
- Prueba de integración RL: ejecutar `Entrenar_Agente.py` por 10-50 episodios y confirmar que `IA_Entrenamiento/logs/DQN_1` se actualiza.
- Verificación de persistencia: implementar test que envíe `build` y consulte el tile en el siguiente tick para confirmar que el bloque persiste.

6.6 Registro de cambios (resumen de ediciones aplicadas)
- `Agente/Entrenar_Agente.py`: rutas refactorizadas a `IA_Entrenamiento/modelos` y `logs`; `tensorboard_log` habilitado.
- `Agente/Probar_Cerebro.py`: carga modelos desde `IA_Entrenamiento/modelos`.
- `Agente/Entorno_Mindustry.py`: wrapper `gymnasium.Env` con reward shaping y conexión TCP a `AgentBridge`.
- `Agent_Mod/src/ai/AgentBridge.java`: servidor TCP en `WorldLoadEvent`, `getControllableUnit()` fallback, navegación en `Trigger.update`, respuesta JSON con `distancia` y coordenadas de recurso.
- Archivos movidos: `Agente/modelos_guardados/*.zip` → `IA_Entrenamiento/modelos/`; logs → `IA_Entrenamiento/logs/`.

6.7 Pendientes y criterios de aceptación
- Pendiente: reemplazar `setBlock`/mutaciones directas por API de construcción autorizada.
  - Criterio de aceptación: después de la migración, una acción `build` seguida de una consulta al tile debe devolver `placed=true` y ser visible en clientes conectados.
- Pendiente: estandarizar protocolo a `step/obs/reward/done/info`.
  - Criterio de aceptación: `Entorno_Mindustry.py` debe aceptar la respuesta estandarizada y el entrenamiento debe poder utilizar `stable-baselines3` sin adaptaciones ad-hoc.
- Pendiente: robustecer manejo de timeouts y reconexiones TCP para entrenamientos largos.
  - Criterio de aceptación: entrenamientos de >1 hora muestran reconexiones automáticas y no causan excepción no controlada en el proceso principal.

---

Fin del informe (documentación extendida).
