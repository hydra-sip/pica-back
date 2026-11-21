package com.hydra.pica.plataforma_pica.reputacion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/** Catálogo de insignias otorgables a los usuarios (HU-804, V64). Se siembra por migración. */
@Entity
@Table(name = "insignia")
@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Insignia {

    @Id
    @Column(name = "codigo", length = 50, nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private String codigo;

    @Column(name = "nombre", length = 100, nullable = false, updatable = false)
    private String nombre;

    @Column(name = "descripcion", length = 255, nullable = false, updatable = false)
    private String descripcion;

    @Column(name = "icono", length = 50, nullable = false, updatable = false)
    private String icono;

    protected Insignia() {
    }
}
