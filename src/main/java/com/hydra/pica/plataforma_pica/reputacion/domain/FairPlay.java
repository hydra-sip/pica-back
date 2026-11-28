package com.hydra.pica.plataforma_pica.reputacion.domain;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** El índice de fair play vigente de un usuario y cuándo se calculó por última vez (HU-802). */
@Entity
@Table(name = "fair_play")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class FairPlay {

    @Id
    @Column(name = "usuario_id", nullable = false)
    @EqualsAndHashCode.Include
    private Long usuarioId;

    @Column(name = "valor", nullable = false)
    private int valor;

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;

    public FairPlay(Long usuarioId, int valor, Instant actualizadoEn) {
        this.usuarioId = usuarioId;
        this.valor = valor;
        this.actualizadoEn = actualizadoEn;
    }
}
