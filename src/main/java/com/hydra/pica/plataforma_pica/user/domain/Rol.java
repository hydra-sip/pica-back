package com.hydra.pica.plataforma_pica.user.domain;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.common.domain.AuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "rol")
@SQLRestriction("eliminado_en IS NULL")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Rol extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "nombre", length = 50, nullable = false)
    private String nombre;

    @Column(name = "nombre_amigable", length = 100, nullable = false)
    private String nombreAmigable;

    @Column(name = "descripcion", columnDefinition = "TEXT")
    private String descripcion;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", length = 20, nullable = false)
    private EstadoGeneral estado;

    @Column(name = "es_sistema", nullable = false)
    private boolean esSistema;

    @Column(name = "eliminado_en")
    private Instant eliminadoEn;

    @Override
    public String toString() {
        return "Rol{"
                + "id=" + id
                + ", nombre='" + nombre + '\''
                + ", nombreAmigable='" + nombreAmigable + '\''
                + ", descripcion='" + descripcion + '\''
                + ", estado=" + estado
                + ", esSistema=" + esSistema
                + ", eliminadoEn=" + eliminadoEn
                + '}';
    }
}
