package com.hydra.pica.plataforma_pica.partido.dto;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.partido.domain.Lado;

/**
 * Un archivo que subió una de las partes (HU-301).
 *
 * @param url  donde se lee el archivo, {@code GET /partidos/{id}/evidencias/{evidenciaId}/archivo}
 * @param hash SHA-256 del contenido, en hexadecimal
 */
public record EvidenciaDetalle(
        Long id,
        Lado lado,
        String equipo,
        String tipoContenido,
        long tamanioBytes,
        String hash,
        String url,
        Instant subidaEn) {
}
