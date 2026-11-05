package com.urbanmove.synthgen;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Punto de entrada: lee los parámetros, ejecuta la simulación, escribe los resultados y muestra un resumen.
 *
 * <pre>
 *   java -jar tools/synthetic-generator/target/synthetic-generator.jar --days 7 --seed 42
 * </pre>
 */
public final class GeneratorCli {

    private static final String HELP = """
            Genera eventos sintéticos de UrbanMove con patrones realistas.

            Opciones:
              --days N             días a simular (por defecto 7)
              --start AAAA-MM-DD   primer día (por defecto 2026-11-09, lunes)
              --seed N             semilla; misma semilla = mismos datos (por defecto 42)
              --vehicles N         tamaño de la flota (por defecto 100)
              --users N            usuarios iniciales (por defecto 200)
              --trips-per-day N    peticiones en un día laborable (por defecto 700)
              --out CARPETA        carpeta de salida (por defecto tools/synthetic-generator/out)
              --no-validate        no validar contra los contratos (más rápido)
              --help               muestra esta ayuda
            """;

    private GeneratorCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Settings settings;
        Path outputDir;
        try {
            Settings s = Settings.defaults();
            Path o = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--days" -> s = s.withDays(positive(args, ++i));
                    case "--start" -> s = s.withStartDate(LocalDate.parse(value(args, ++i)));
                    case "--seed" -> s = s.withSeed(Long.parseLong(value(args, ++i)));
                    case "--vehicles" -> s = s.withVehicles(positive(args, ++i));
                    case "--users" -> s = s.withUsers(positive(args, ++i));
                    case "--trips-per-day" -> s = s.withTripsPerDay(positive(args, ++i));
                    case "--out" -> o = Path.of(value(args, ++i));
                    case "--no-validate" -> s = s.withValidate(false);
                    case "--help", "-h" -> {
                        out.print(HELP);
                        return 0;
                    }
                    default -> throw new IllegalArgumentException("Opción desconocida: " + args[i]);
                }
            }
            settings = s;
            outputDir = (o != null ? o : ToolPaths.defaultOutputDir()).toAbsolutePath().normalize();
        } catch (IllegalArgumentException | DateTimeParseException e) {
            err.println("ERROR: " + e.getMessage());
            err.print(HELP);
            return 2;
        }

        out.printf("Simulando %d días desde %s (semilla %d)...%n", settings.days(), settings.startDate(),
                settings.seed());
        long started = System.nanoTime();
        ObjectNode summary;
        try {
            summary = OutputWriter.write(Simulator.generate(settings), outputDir);
        } catch (InvalidGeneratedEventException | UnsafeOutputDirException e) {
            err.println("ERROR: " + e.getMessage());
            return 1;
        }

        ObjectNode rides = (ObjectNode) summary.get("rides");
        ObjectNode reservations = (ObjectNode) summary.get("reservations");
        out.printf("Listo en %.1f s -> %s%n", (System.nanoTime() - started) / 1e9, outputDir);
        out.println("  Eventos: " + summary.get("events").get("total").asInt()
                + (settings.validate() ? "  (todos válidos según los contratos)" : ""));
        out.println("  Viajes: " + rides.get("total").asInt() + " | duración media "
                + rides.get("avgDurationMinutes").asDouble() + " min | coste medio "
                + rides.get("avgCostEur").asDouble() + " EUR");
        out.println("  Reservas: " + reservations.get("created").asInt() + " | canceladas "
                + reservations.get("cancelled").asInt() + " | caducadas " + reservations.get("expired").asInt());
        out.println("  Mantenimiento: " + summary.get("maintenance").get("withdrawalsByReason"));
        out.println("  Demanda no atendida: " + summary.get("unmetDemand").get("total").asInt() + " peticiones");
        out.println("  Horas-zona con déficit según la regla del enunciado: "
                + summary.get("hotspotCandidates").size());
        return 0;
    }

    private static String value(String[] args, int index) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Falta el valor de " + args[index - 1]);
        }
        return args[index];
    }

    private static int positive(String[] args, int index) {
        int number = Integer.parseInt(value(args, index));
        if (number <= 0) {
            throw new IllegalArgumentException(args[index - 1] + " debe ser mayor que 0");
        }
        return number;
    }
}
