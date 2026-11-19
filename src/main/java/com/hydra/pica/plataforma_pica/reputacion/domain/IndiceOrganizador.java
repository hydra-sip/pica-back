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

/** El índice vigente de confiabilidad de un organizador y cuándo se actualizó por última vez (HU-806). */
@Entity
@Table(name = "indice_organizador")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class IndiceOrganizador {

    @Id
    @Column(name = "usuario_id", nullable = false)
    @EqualsAndHashCode.Include
    private Long usuarioId;

    @Column(name = "valor", nullable = false)
    private int valor;

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;

    public IndiceOrganizador(Long usuarioId, int valor, Instant actualizadoEn) {
        this.usuarioId = usuarioId;
        this.valor = valor;
        this.actualizadoEn = actualizadoEn;
    }
}
