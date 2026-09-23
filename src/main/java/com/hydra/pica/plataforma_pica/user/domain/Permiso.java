package com.hydra.pica.plataforma_pica.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

/**
 * Catálogo de permisos (V4). Es de solo lectura: se carga por migración y no hay API que lo
 * modifique. El {@code codigo} es lo que va en el JWT y en {@code @PreAuthorize("hasAuthority(...)")}.
 */
@Entity
@Table(name = "permiso")
@Immutable
@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Permiso {

    @Id
    @Column(name = "id", nullable = false)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "codigo", length = 50, nullable = false)
    private String codigo;

    @Enumerated(EnumType.STRING)
    @Column(name = "modulo", length = 30, nullable = false)
    private Modulo modulo;

    @Column(name = "descripcion", length = 255, nullable = false)
    private String descripcion;

    protected Permiso() {
    }

    @Override
    public String toString() {
        return "Permiso{id=" + id + ", codigo='" + codigo + "', modulo=" + modulo + '}';
    }
}
