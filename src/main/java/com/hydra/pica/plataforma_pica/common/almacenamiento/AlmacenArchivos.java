package com.hydra.pica.plataforma_pica.common.almacenamiento;

import java.util.Optional;

/**
 * Guarda archivos por clave ({@code torneos/9/reglamento.pdf}). Hay uno por instalación, según
 * {@code app.almacenamiento.tipo}: {@link AlmacenLocal} o {@link AlmacenSupabase}. Guardar con una clave que ya
 * existe la reemplaza.
 */
public interface AlmacenArchivos {

    void guardar(String clave, byte[] contenido, String tipoContenido);

    Optional<byte[]> leer(String clave);

    /** Si no existe, no hace nada. */
    void borrar(String clave);
}
