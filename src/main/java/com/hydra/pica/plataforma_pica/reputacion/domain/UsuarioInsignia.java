package com.hydra.pica.plataforma_pica.reputacion.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/** Insignia obtenida por un usuario en la plataforma (HU-804, V64). */
@Entity
@Table(name = "usuario_insignia")
@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class UsuarioInsignia {

    @EmbeddedId
    @EqualsAndHashCode.Include
    private UsuarioInsigniaId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("codigo")
    @JoinColumn(name = "codigo", nullable = false)
    private Insignia insignia;

    @Column(name = "otorgada_en", nullable = false, updatable = false)
    private Instant otorgadaEn;

    protected UsuarioInsignia() {
    }
}
