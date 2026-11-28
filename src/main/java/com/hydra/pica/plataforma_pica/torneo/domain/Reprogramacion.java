package com.hydra.pica.plataforma_pica.torneo.domain;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Una reprogramación del torneo (CR-01, PICA-391): las fechas de antes y las nuevas, el motivo y hasta cuándo los
 * inscriptos pueden rechazar la nueva fecha. {@code plazoProcesadoEn} queda en null mientras el plazo no se resolvió
 * ({@link com.hydra.pica.plataforma_pica.torneo.service.VencimientoReprogramacion}); la base admite una sola así por
 * torneo.
 */
@Entity
@Table(name = "reprogramacion")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Reprogramacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "torneo_id", nullable = false)
    private Torneo torneo;

    @Column(name = "fecha_inicio_anterior", nullable = false)
    private Instant fechaInicioAnterior;

    @Column(name = "fecha_fin_anterior")
    private Instant fechaFinAnterior;

    @Column(name = "fecha_inicio_nueva", nullable = false)
    private Instant fechaInicioNueva;

    @Column(name = "fecha_fin_nueva")
    private Instant fechaFinNueva;

    @Column(name = "cierre_inscripcion_nuevo", nullable = false)
    private Instant cierreInscripcionNuevo;

    @Column(name = "rechazo_hasta", nullable = false)
    private Instant rechazoHasta;

    @Column(name = "motivo", length = 500, nullable = false)
    private String motivo;

    @Column(name = "plazo_procesado_en")
    private Instant plazoProcesadoEn;

    /** Cuándo salió el recordatorio del día antes de que venza el plazo (CR-01-4, V70); sale una sola vez. */
    @Column(name = "recordatorio_enviado_en")
    private Instant recordatorioEnviadoEn;

    /** Los que se bajaron en esta reprogramación (CR-01-3), para el aviso al organizador al vencer el plazo. */
    @Column(name = "rechazos", nullable = false)
    private int rechazos;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creada_por", nullable = false)
    private Usuario creadaPor;

    @Column(name = "creada_en", nullable = false)
    private Instant creadaEn = Instant.now();
}
