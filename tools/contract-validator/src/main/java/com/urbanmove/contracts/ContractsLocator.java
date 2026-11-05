package com.urbanmove.contracts;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Encuentra la carpeta de contratos (docs/contracts/asyncapi).
 */
public final class ContractsLocator {

    public static final String CONTRACTS_RELATIVE_PATH = "docs/contracts/asyncapi";
    public static final String ENV_VARIABLE = "URBANMOVE_CONTRACTS_DIR";
    public static final String SYSTEM_PROPERTY = "urbanmove.contracts.dir";

    private ContractsLocator() {
    }

    /** Raíz del repo: la primera carpeta, subiendo desde la actual, que contiene docs/contracts/asyncapi. */
    public static Path findRepoRoot() {
        return findRepoRoot(Path.of("").toAbsolutePath());
    }

    public static Path findRepoRoot(Path start) {
        for (Path dir = start.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve(CONTRACTS_RELATIVE_PATH))) {
                return dir;
            }
        }
        throw new IllegalStateException("No encuentro " + CONTRACTS_RELATIVE_PATH + " subiendo desde " + start
                + ". Ejecuta el programa desde dentro del repo o define " + ENV_VARIABLE + ".");
    }

    public static Path findContractsDir() {
        String configured = System.getProperty(SYSTEM_PROPERTY, System.getenv(ENV_VARIABLE));
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return findRepoRoot().resolve(CONTRACTS_RELATIVE_PATH);
    }
}
