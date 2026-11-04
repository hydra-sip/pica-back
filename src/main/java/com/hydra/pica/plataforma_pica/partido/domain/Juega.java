package com.hydra.pica.plataforma_pica.partido.domain;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.common.domain.AuditableEntity;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

/** Una de las dos partes de un partido: un lado por inscripción. */
@Entity
@Table(name = "juega")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Juega extends AuditableEntity {

    @EmbeddedId
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private JuegaId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("partidoId")
    @JoinColumn(name = "partido_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private Partido partido;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("inscripcionId")
    @JoinColumn(name = "inscripcion_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private Inscripcion inscripcion;

    @Enumerated(EnumType.STRING)
    @Column(name = "lado", length = 10, nullable = false)
    private Lado lado;

    @Column(name = "puntaje")
    private Integer puntaje;

    @Column(name = "penales")
    private Integer penales;

    @Column(name = "es_ganador")
    private Boolean esGanador;

    @Column(name = "checkin_en")
    private Instant checkinEn;

    protected Juega() {
    }

    public Juega(Partido partido, Inscripcion inscripcion, Lado lado) {
        this.partido = partido;
        this.inscripcion = inscripcion;
        this.lado = lado;
        this.id = new JuegaId(partido.getId(), inscripcion.getId());
    }
}
