package com.hydra.pica.plataforma_pica.reputacion.dto;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.reputacion.domain.UsuarioInsignia;

/** Insignia pública exhibida en el perfil del jugador (HU-804). */
public record InsigniaObtenida(
        String codigo,
        String nombre,
        String descripcion,
        String icono,
        Instant otorgadaEn) {

    public static InsigniaObtenida desde(UsuarioInsignia asignada) {
        return new InsigniaObtenida(
                asignada.getInsignia().getCodigo(),
                asignada.getInsignia().getNombre(),
                asignada.getInsignia().getDescripcion(),
                asignada.getInsignia().getIcono(),
                asignada.getOtorgadaEn());
    }
}
