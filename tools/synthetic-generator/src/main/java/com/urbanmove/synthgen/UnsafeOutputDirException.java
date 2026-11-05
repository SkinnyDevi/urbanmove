package com.urbanmove.synthgen;

/** La carpeta de salida tiene ficheros que no creó el generador: no se borra por seguridad. */
public class UnsafeOutputDirException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnsafeOutputDirException(String message) {
        super(message);
    }
}
