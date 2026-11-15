package com.hydra.pica.plataforma_pica.partido.dto;

import java.time.Instant;
import java.util.List;

/**
 * Todo lo que hace falta para recalcular el sorteo de los grupos (CR-02, PICA-397) en el portal de verificación: la
 * lista sellada ({@code listaHash}) y las inscripciones confirmadas, el compromiso ({@code SHA-256(listaHash:ronda)})
 * publicado al sellarla, la ronda de drand y su valor (la semilla) con la firma, y el reparto. Antes del sorteo, la
 * semilla, la firma y los grupos vienen vacíos; el compromiso ya está.
 *
 * @param version        la de {@code SorteoGrupos}: 1 es el orden de la llave repartido en serpentina
 * @param inscripciones  las confirmadas que entraron al sorteo, por id
 * @param cantidadGrupos los grupos con el cupo máximo ({@code cupoMaximo / tamanioGrupo})
 * @param semillaUrl     donde se consulta la ronda en drand, para comparar la semilla y la firma
 */
public record SorteoGruposDetalle(
        Long torneoId,
        String torneoNombre,
        int version,
        String listaHash,
        Instant listaSelladaEn,
        List<Long> inscripciones,
        Long semillaRonda,
        String compromisoHash,
        String semilla,
        String semillaFirma,
        String semillaUrl,
        int cantidadGrupos,
        List<GrupoSorteado> grupos) {

    /** @param integrantes en el orden del sorteo */
    public record GrupoSorteado(String letra, List<Integrante> integrantes) {
    }

    public record Integrante(Long inscripcionId, String nombre) {
    }
}
