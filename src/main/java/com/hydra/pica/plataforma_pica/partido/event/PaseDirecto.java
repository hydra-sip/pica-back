package com.hydra.pica.plataforma_pica.partido.event;

/**
 * Lo publica {@code AvanceLlave} cuando un lado gana sin jugar porque el otro lugar del partido quedó vacante: el
 * partido hermano terminó sin ganador (HU-309). Lo escucha el aviso al capitán que pasa y al organizador; el tiempo
 * real lo recibe por el {@link ResultadoConfirmado} del mismo partido.
 *
 * @param inscripcionId la que pasa de ronda
 */
public record PaseDirecto(Long torneoId, Long partidoId, Long inscripcionId) {
}
