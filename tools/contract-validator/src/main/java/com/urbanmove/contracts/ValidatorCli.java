package com.urbanmove.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Valida ficheros de eventos desde la terminal.
 * Termina con código 0 si todo es válido, 1 si hay algún evento inválido y 2 si se usa mal.
 */
public final class ValidatorCli {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ValidatorCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, ContractValidator.fromDefaultLocation()));
    }

    static int run(String[] args, PrintStream out, ContractValidator validator) {
        if (args.length == 0) {
            out.println("Uso: java -jar contract-validator-1.0.0-cli.jar FICHERO_O_CARPETA...");
            out.println("Ejemplo: java -jar tools/contract-validator/target/contract-validator-1.0.0-cli.jar "
                    + "docs/contracts/asyncapi/examples");
            return 2;
        }
        int total = 0;
        int invalid = 0;
        for (Path file : expand(args)) {
            List<JsonNode> events;
            try {
                events = load(file);
            } catch (IOException e) {
                out.println("[ERROR] No puedo leer " + file + ": " + e.getMessage());
                return 2;
            }
            for (int i = 0; i < events.size(); i++) {
                total++;
                JsonNode event = events.get(i);
                List<String> errors = validator.validate(event);
                if (!errors.isEmpty()) {
                    invalid++;
                    String label = event.hasNonNull("eventId") ? event.get("eventId").asText() : "#" + i;
                    out.println("[INVÁLIDO] " + file + " · " + label);
                    errors.forEach(error -> out.println("    - " + error));
                }
            }
        }
        out.println((total - invalid) + "/" + total + " eventos válidos");
        return invalid == 0 ? 0 : 1;
    }

    private static List<Path> expand(String[] args) {
        List<Path> files = new ArrayList<>();
        for (String arg : args) {
            Path path = Path.of(arg);
            if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.filter(Files::isRegularFile)
                            .filter(p -> p.toString().endsWith(".json") || p.toString().endsWith(".jsonl"))
                            .sorted()
                            .forEach(files::add);
                } catch (IOException e) {
                    throw new IllegalStateException("No puedo recorrer " + path, e);
                }
            } else {
                files.add(path);
            }
        }
        return files;
    }

    static List<JsonNode> load(Path file) throws IOException {
        String text = Files.readString(file);
        List<JsonNode> events = new ArrayList<>();
        if (file.toString().endsWith(".jsonl")) {
            for (String line : text.split("\\R")) {
                if (!line.isBlank()) {
                    events.add(MAPPER.readTree(line));
                }
            }
            return events;
        }
        JsonNode root = MAPPER.readTree(text);
        if (root.isArray()) {
            root.forEach(events::add);
        } else {
            events.add(root);
        }
        return events;
    }
}
