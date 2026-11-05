package com.urbanmove.synthgen;

import com.urbanmove.contracts.ContractsLocator;

import java.nio.file.Path;

/** Dónde están las cosas del generador, buscando la raíz del repo hacia arriba desde la carpeta actual. */
public final class ToolPaths {

    private ToolPaths() {
    }

    public static Path toolDir() {
        return ContractsLocator.findRepoRoot().resolve("tools").resolve("synthetic-generator");
    }

    public static Path configDir() {
        return toolDir().resolve("config");
    }

    public static Path defaultOutputDir() {
        return toolDir().resolve("out");
    }
}
