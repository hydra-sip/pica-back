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
 * Un valor que fija la plataforma (V26). Se cargan por migración y el admin los edita (HU-702, PICA-282).
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

    /** {@code por} es el id del usuario, como en el resto de las columnas {@code *_por}. */
    public void cambiar(BigDecimal valor, String por) {
        this.valor = valor;
        this.modificadoEn = Instant.now();
        this.modificadoPor = por;
    }
}
