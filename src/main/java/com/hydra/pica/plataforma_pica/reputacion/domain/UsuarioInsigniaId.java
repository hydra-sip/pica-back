package com.hydra.pica.plataforma_pica.reputacion.domain;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Clave compuesta de {@link UsuarioInsignia}: impide otorgar la misma insignia dos veces al mismo usuario. */
@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class UsuarioInsigniaId implements Serializable {

    @Column(name = "usuario_id", nullable = false)
    private Long usuarioId;

    @Column(name = "codigo", length = 50, nullable = false)
    private String codigo;
}
