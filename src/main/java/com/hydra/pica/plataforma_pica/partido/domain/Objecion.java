package com.hydra.pica.plataforma_pica.partido.domain;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Una por partido (comparte su PK), de una de las partes. Los campos del laudo quedan en null
 * hasta que se resuelve y entonces se completan todos juntos: la base rechaza un laudo a medias.
 */
@Entity
@Table(name = "objecion")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Objecion {

    @Id
    @Column(name = "partido_id", nullable = false)
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private Long partidoId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "partido_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private Partido partido;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inscripcion_id", nullable = false)
    private Inscripcion inscripcion;

    @Column(name = "motivo", columnDefinition = "TEXT", nullable = false)
    private String motivo;

    /** Resultado que propone quien objeta. */
    @Column(name = "puntaje_local", nullable = false)
    private Integer puntajeLocal;

    @Column(name = "puntaje_visitante", nullable = false)
    private Integer puntajeVisitante;

    /** Solo con empate. */
    @Column(name = "penales_local")
    private Integer penalesLocal;

    @Column(name = "penales_visitante")
    private Integer penalesVisitante;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private Instant creadoEn;

    // Laudo
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resuelta_por")
    private Usuario resueltaPor;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", length = 20)
    private Decision decision;

    @Column(name = "justificacion", columnDefinition = "TEXT")
    private String justificacion;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "evidencia_id")
    private Evidencia evidencia;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "laudo_hash", length = 64)
    private String laudoHash;

    @Column(name = "resuelta_en")
    private Instant resueltaEn;

    protected Objecion() {
    }

    public Objecion(Partido partido) {
        this.partido = partido;
    }

    @PrePersist
    void alPersistir() {
        if (creadoEn == null) {
            creadoEn = Instant.now();
        }
    }
}
