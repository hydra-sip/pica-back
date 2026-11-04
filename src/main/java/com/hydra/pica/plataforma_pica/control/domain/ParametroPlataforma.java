package com.hydra.pica.plataforma_pica.control.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * Un valor que fija la plataforma (V26). Por ahora solo se lee: se cargan por migración y editarlos es HU-702.
 */
@Entity
@Table(name = "parametro_plataforma")
@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ParametroPlataforma {

    @Id
    @Column(name = "clave", length = 50, nullable = false)
    @EqualsAndHashCode.Include
    private String clave;

    @Column(name = "valor", precision = 12, scale = 2, nullable = false)
    private BigDecimal valor;

    @Column(name = "descripcion", columnDefinition = "TEXT", nullable = false)
    private String descripcion;

    @Column(name = "modificado_en", nullable = false)
    private Instant modificadoEn;

    @Column(name = "modificado_por", length = 100)
    private String modificadoPor;
}
