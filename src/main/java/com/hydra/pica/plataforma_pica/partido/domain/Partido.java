package com.hydra.pica.plataforma_pica.partido.domain;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.common.domain.AuditableEntity;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.torneo.domain.Cancha;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

/**
 * Los partidos de todas las rondas existen desde que se genera el bracket. El árbitro tiene que
 * estar designado en el torneo y la cancha ser del torneo (FK compuestas de V9).
 */
@Entity
@Table(name = "partido")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Partido extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "torneo_id", nullable = false)
    private Torneo torneo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "arbitro_id", nullable = false)
    private Usuario arbitro;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancha_id")
    private Cancha cancha;

    @Column(name = "ronda", nullable = false)
    private Integer ronda;

    @Column(name = "posicion", nullable = false)
    private Integer posicion;

    @Column(name = "fecha_programada")
    private Instant fechaProgramada;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", length = 30, nullable = false)
    private EstadoPartido estado = EstadoPartido.PROGRAMADO;

    @Column(name = "resultado_cargado_en")
    private Instant resultadoCargadoEn;

    @Column(name = "objecion_vence_en")
    private Instant objecionVenceEn;

    /** La parte que cargó el resultado (V22); la otra es la que lo acepta u objeta. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resultado_cargado_por")
    private Inscripcion resultadoCargadoPor;
}
