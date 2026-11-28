package com.hydra.pica.plataforma_pica.control.service.fraude;

/**
 * Una regla de detección de fraude (HU-705). Cada una tiene su consulta y sus umbrales de
 * {@link com.hydra.pica.plataforma_pica.control.service.ParametrosPlataforma}, y no duplica una alerta PENDIENTE.
 */
public interface ReglaDeFraude {

    /** Pasada completa sobre la base: evalúa a todos los candidatos y devuelve cuántas alertas creó. */
    int evaluarTodo();
}
