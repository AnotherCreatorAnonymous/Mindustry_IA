package ai;

import arc.Core;
import arc.Events;
import arc.math.Mathf;
import arc.util.Log;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.mod.Mod;
import mindustry.gen.Unit;
import mindustry.gen.Groups;
import mindustry.game.Team;
import mindustry.content.UnitTypes;
import mindustry.world.Tile;

import java.io.*;
import java.net.*;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class AgentBridge extends Mod {
    private ServerSocket serverSocket;
    private Unit agenteUnidad;

    // FIX 4: volatile garantiza visibilidad entre el hilo TCP (escritura)
    //         y el Trigger.update del game loop (lectura).
    //         Sin volatile, la JVM puede cachear estos valores por hilo
    //         y Trigger.update nunca "ve" los valores nuevos.
    private volatile float targetWorldX = -1;
    private volatile float targetWorldY = -1;
    private volatile boolean isNavigating = false;

    @Override
    public void init() {
        Events.on(WorldLoadEvent.class, e -> iniciarServidorTCP());

        // FIX 1a: El Core.app.post() aquí solo envuelve el REGISTRO del Trigger,
        //          no su ejecución. Lo eliminamos: el registro puede hacerse
        //          directamente en init() sin riesgo.
        //          El lambda dentro de Trigger.update SÍ corre en el game loop
        //          automáticamente (esa es la semántica del evento Trigger.update).
        arc.Events.run(mindustry.game.EventType.Trigger.update, () -> {
            Unit u = getControllableUnit();
            if (isNavigating && u != null && !u.dead()) {
                float dst = Mathf.dst(u.x, u.y, targetWorldX, targetWorldY);
                if (dst > 8f) {
                    float angle = u.angleTo(targetWorldX, targetWorldY);
                    // Esta escritura ocurre en el game loop → seguro
                    u.vel.set(Mathf.cosDeg(angle) * u.speed(), Mathf.sinDeg(angle) * u.speed());
                } else {
                    isNavigating = false;
                    u.vel.set(0, 0);
                }
            }
        });
    }

    public Unit getControllableUnit() {
        if (Vars.player != null && Vars.player.unit() != null && !Vars.player.unit().dead()) {
            return Vars.player.unit();
        }
        Unit u = Groups.unit.find(unit -> unit.team == Team.sharded && unit.type == UnitTypes.mono);
        if (u != null) return u;
        if (Vars.world != null && Vars.world.tiles != null && agenteUnidad == null) {
            agenteUnidad = UnitTypes.mono.spawn(Team.sharded, Vars.world.width() / 2 * 8f, Vars.world.height() / 2 * 8f);
            return agenteUnidad;
        }
        return null;
    }

    private void iniciarServidorTCP() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(5050);
                Log.info("AGENT BRIDGE: Servidor RL autónomo iniciado en puerto 5050.");
                while (true) {
                    try (Socket socket = serverSocket.accept();
                         BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                         PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

                        String linea;
                        while ((linea = in.readLine()) != null) {
                            procesarComando(linea, out);
                        }

                    } catch (Exception e) {
                        Log.err("AGENT BRIDGE: Cliente desconectado o error de socket: " + e.getMessage());
                        // Limpieza segura: delegamos al game loop
                        Core.app.post(() -> {
                            isNavigating = false;
                            Unit u = getControllableUnit();
                            if (u != null) u.resetController();
                        });
                    }
                }
            } catch (Exception e) {
                Log.err("AGENT BRIDGE: Error fatal en ServerSocket: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Procesa un comando JSON recibido del cliente Python.
     * Toda mutación del estado del juego se delega al game loop
     * mediante Core.app.post() + CountDownLatch para sincronizar
     * la respuesta con el estado real post-mutación.
     */
    private void procesarComando(String linea, PrintWriter out) {
        Jval json;
        try {
            json = Jval.read(linea);
        } catch (Exception e) {
            out.println("{\"error\": \"JSON malformado\"}");
            return;
        }

        String action = json.getString("action", "none");

        // Latch para sincronizar: esperamos que el game loop ejecute
        // la mutación y construya la observación antes de responder.
        CountDownLatch latch = new CountDownLatch(1);
        // Array de 1 elemento para pasar el resultado fuera del lambda
        String[] respuesta = {"{\"error\": \"Timeout esperando game loop\"}"};

        if (action.equals("move")) {
            // FIX 2 (move): Leemos u.x / u.y y aplicamos vel dentro del game loop.
            //               Antes se leía u.x desde el hilo TCP (race condition).
            final int dx = json.getInt("x", 0);
            final int dy = json.getInt("y", 0);

            Core.app.post(() -> {
                try {
                    Unit u = getControllableUnit();
                    if (u == null) {
                        respuesta[0] = "{\"error\": \"No hay unidad controlable\"}";
                        return;
                    }
                    // La lectura de u.x/u.y es segura: estamos en el game loop
                    targetWorldX = u.x + dx * 8f;
                    targetWorldY = u.y + dy * 8f;
                    isNavigating = true;

                    respuesta[0] = construirObservacion(u);
                } finally {
                    latch.countDown();
                }
            });

        } else if (action.equals("reset")) {
            // FIX 2 (reset): u.set() y u.vel.set() DEBEN correr en el game loop.
            //                Antes corrían en el hilo TCP → motor los ignoraba.
            Core.app.post(() -> {
                try {
                    Unit u = getControllableUnit();
                    if (u == null) {
                        respuesta[0] = "{\"error\": \"No hay unidad controlable\"}";
                        return;
                    }
                    // Teletransporte aleatorio dentro del mapa, con margen de 100px a los bordes
                    float margen = 100f;
                    float maxX = Vars.world.width()  * 8f - margen;
                    float maxY = Vars.world.height() * 8f - margen;
                    float randomX = Mathf.random(margen, maxX);
                    float randomY = Mathf.random(margen, maxY);

                    // Estas escrituras son seguras: estamos en el game loop
                    u.set(randomX, randomY);
                    u.vel.set(0f, 0f);
                    isNavigating = false;

                    respuesta[0] = construirObservacion(u);
                } finally {
                    latch.countDown();
                }
            });

        } else {
            // action "none" u otros: solo leer estado, sin mutar
            Core.app.post(() -> {
                try {
                    Unit u = getControllableUnit();
                    if (u == null) {
                        respuesta[0] = "{\"error\": \"No hay unidad controlable\"}";
                        return;
                    }
                    respuesta[0] = construirObservacion(u);
                } finally {
                    latch.countDown();
                }
            });
        }

        // Esperamos a que el game loop termine (máx. 2 segundos)
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                Log.warn("AGENT BRIDGE: Timeout esperando el game loop para acción: " + action);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        out.println(respuesta[0]);
    }

    /**
     * Construye el JSON de observación a partir del estado actual de la unidad.
     * DEBE llamarse desde dentro del game loop (Core.app.post o Trigger.update).
     * FIX 3: radarSimple() también corre aquí, dentro del game loop,
     *         evitando ConcurrentModificationException al iterar Vars.world.
     */
    private String construirObservacion(Unit u) {
        float[] radar = radarSimple(u);
        float closest = radar[0];
        float tx = radar[1];
        float ty = radar[2];

        // tile.x / tile.y son coordenadas Tile (Python las multiplica por 8 → correcto)
        return String.format(Locale.US,
            "{\"distancia\": %.2f, \"drone_x\": %.2f, \"drone_y\": %.2f, \"ore_x\": %.2f, \"ore_y\": %.2f}",
            closest, u.x, u.y, tx, ty);
    }

    /**
     * Escanea el mapa en busca de la veta de cobre más cercana.
     * FIX 3: Este método debe ejecutarse en el game loop para evitar
     *         condiciones de carrera al leer Vars.world.tiles.
     */
    private float[] radarSimple(Unit u) {
        float closest = Float.MAX_VALUE;
        float tx = 0f;
        float ty = 0f;

        if (Vars.world == null) return new float[]{closest, tx, ty};

        for (int x = 0; x < Vars.world.width(); x++) {
            for (int y = 0; y < Vars.world.height(); y++) {
                Tile tile = Vars.world.tile(x, y);
                if (tile != null && tile.drop() != null && tile.drop().name.equals("copper")) {
                    // Comparamos con worldx()/worldy() (píxeles) para que 'closest' esté en píxeles,
                    // coherente con lo que Python espera como 'distancia'.
                    float d = Mathf.dst(u.x, u.y, tile.worldx(), tile.worldy());
                    if (d < closest) {
                        closest = d;
                        // Devolvemos coordenadas Tile (no píxeles): Python multiplica por 8
                        tx = tile.x;
                        ty = tile.y;
                    }
                }
            }
        }

        return new float[]{closest, tx, ty};
    }
}
