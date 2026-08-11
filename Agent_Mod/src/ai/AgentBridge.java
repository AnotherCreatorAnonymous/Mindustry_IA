package ai;

import arc.Core;
import arc.Events;
import arc.math.Mathf;
import arc.util.Log;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.content.Items;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.mod.Mod;
import mindustry.gen.Unit;
import mindustry.gen.Groups;
import mindustry.game.Team;
import mindustry.content.UnitTypes;
import mindustry.gen.Player;
import mindustry.world.Tile;
import mindustry.type.Item;

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
            agenteUnidad = UnitTypes.mono.spawn(Team.sharded, Vars.world.width() / 2f * 8f, Vars.world.height() / 2f * 8f);
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
            Core.app.post(() -> {
                try {
                    if (Vars.player == null) {
                        respuesta[0] = "{\"error\": \"No hay jugador local\"}";
                        latch.countDown();
                        return;
                    }

                    isNavigating = false;
                    targetWorldX = -1f;
                    targetWorldY = -1f;

                    // Respawn nativo: equivalente a pulsar V.
                    // Se ejecuta directo sobre el jugador para evitar la latencia del packet remoto.
                    Vars.player.clearUnit();
                    Vars.player.checkSpawn();
                    Vars.player.deathTimer = Player.deathDelay + 1f;

                    Unit u = getControllableUnit();
                    if (u == null) {
                        respuesta[0] = "{\"error\": \"No se pudo recuperar la unidad tras el respawn\"}";
                    } else {
                        respuesta[0] = construirObservacion(u);
                    }
                    latch.countDown();
                } catch (Exception e) {
                    respuesta[0] = "{\"error\": \"Falló el respawn nativo\"}";
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
     */
    private String construirObservacion(Unit u) {
        StringBuilder json = new StringBuilder(256);
        json.append('{');
        json.append(String.format(Locale.US, "\"drone_x\": %.2f, \"drone_y\": %.2f", u.x, u.y));

        appendOreObservation(json, u, "copper", Items.copper);
        appendOreObservation(json, u, "lead", Items.lead);
        appendOreObservation(json, u, "coal", Items.coal);
        appendOreObservation(json, u, "titanium", Items.titanium);
        appendOreObservation(json, u, "thorium", Items.thorium);
        appendOreObservation(json, u, "scrap", Items.scrap);

        json.append('}');
        return json.toString();
    }

    /**
     * Serializa el mineral más cercano usando el indexador espacial nativo.
     * Coordenadas X/Y se devuelven en tiles; la distancia se devuelve en píxeles.
     * Si el mineral no existe en el mapa, se inyectan -1 en los tres campos.
     */
    private void appendOreObservation(StringBuilder json, Unit u, String key, Item item) {
        Tile ore = Vars.indexer.findClosestOre(u, item);

        json.append(", ");
        if (ore == null) {
            json.append(String.format(Locale.US,
                "\"%s_dst\": -1, \"%s_x\": -1, \"%s_y\": -1",
                key, key, key));
            return;
        }

        float dst = Mathf.dst(u.x, u.y, ore.worldx(), ore.worldy());
        json.append(String.format(Locale.US,
            "\"%s_dst\": %.2f, \"%s_x\": %d, \"%s_y\": %d",
            key, dst, key, ore.x, key, ore.y));
    }

}
