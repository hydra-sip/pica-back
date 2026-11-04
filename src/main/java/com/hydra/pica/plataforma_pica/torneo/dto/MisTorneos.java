package com.hydra.pica.plataforma_pica.torneo.dto;

import java.util.List;
import java.util.Map;

import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import org.springframework.data.web.PagedModel.PageMetadata;

/**
 * Mis torneos (PICA-201): la página con la misma forma que las demás ({@code content} y {@code page}), más lo
 * que necesitan las pestañas y el combo de la pantalla.
 *
 * @param porEstado cuántos hay en cada estado (todos, con 0 si no hay) con la búsqueda y la disciplina, sin el estado
 * @param disciplinas las de los torneos del organizador, para el filtro
 */
public record MisTorneos(
        List<MiTorneoResumen> content,
        PageMetadata page,
        Map<EstadoTorneo, Long> porEstado,
        List<String> disciplinas) {
}
