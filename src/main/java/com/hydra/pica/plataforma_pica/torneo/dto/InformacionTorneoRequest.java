package com.hydra.pica.plataforma_pica.torneo.dto;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import com.hydra.pica.plataforma_pica.torneo.domain.Formato;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Pasos 1 y 2 del asistente de crear torneo (PICA-199), para el alta y la modificación. El front manda todo cada
 * vez: lo que viene en null queda vacío. En BORRADOR solo el nombre y la disciplina son obligatorios; lo que
 * compara un campo con otro (fechas, cupos, sede, nómina) lo valida {@code TorneoService}, y que esté todo lo
 * exige la publicación.
 *
 * @param formato si no viene, eliminación directa (el único que hay)
 * @param canchas nombres de las canchas de la sede; las que no existían se crean
 * @param otros lo que vino y no es de los pasos 1 y 2 (por ejemplo, el valor de la inscripción): se rechaza con 400
 *              en vez de ignorarse en silencio (HU-202)
 */
public record InformacionTorneoRequest(
        @NotBlank @Size(max = 100) String nombre,
        @NotBlank @Size(max = 50) String disciplina,
        @Size(max = 2000) String descripcion,
        Formato formato,
        Modalidad modalidad,
        TipoParticipante tipoParticipante,
        @Size(max = 255) String sede,
        List<@NotBlank @Size(max = 100) String> canchas,
        Instant fechaInicio,
        Instant fechaFin,
        Instant cierreInscripcionEn,
        @Size(max = 2000) String reglamentoTexto,
        Integer cupoMaximo,
        Integer cupoMinimo,
        @Positive Integer tamanioEquipo,
        @Positive Integer nominaMaxima,
        @JsonAnySetter @JsonAnyGetter Map<String, Object> otros) {

    public InformacionTorneoRequest {
        otros = otros == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(otros));
    }

    public InformacionTorneoRequest(String nombre, String disciplina, String descripcion, Formato formato,
            Modalidad modalidad, TipoParticipante tipoParticipante, String sede, List<String> canchas,
            Instant fechaInicio, Instant fechaFin, Instant cierreInscripcionEn, String reglamentoTexto,
            Integer cupoMaximo, Integer cupoMinimo, Integer tamanioEquipo, Integer nominaMaxima) {
        this(nombre, disciplina, descripcion, formato, modalidad, tipoParticipante, sede, canchas, fechaInicio,
                fechaFin, cierreInscripcionEn, reglamentoTexto, cupoMaximo, cupoMinimo, tamanioEquipo, nominaMaxima,
                Map.of());
    }
}
