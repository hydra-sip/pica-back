package com.hydra.pica.plataforma_pica.control.domain;

import java.time.Instant;
import java.util.Map;

import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Alerta generada automáticamente ante una actividad sospechosa en torneos, rifas o billeteras (HU-705, PICA-416).
 */
@Entity
@Table(name = "alerta_fraude")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AlertaFraude {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "regla", length = 40, nullable = false)
    private ReglaFraude regla;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "torneo_id")
    private Torneo torneo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rifa_id")
    private Rifa rifa;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Column(name = "motivo", columnDefinition = "TEXT", nullable = false)
    private String motivo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "datos", columnDefinition = "JSONB", nullable = false)
    private Map<String, Object> datos;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", length = 20, nullable = false)
    private EstadoAlertaFraude estado = EstadoAlertaFraude.PENDIENTE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "revisada_por")
    private Usuario revisadaPor;

    @Column(name = "revisada_en")
    private Instant revisadaEn;

    @Column(name = "nota", columnDefinition = "TEXT")
    private String nota;

    @Column(name = "creada_en", nullable = false, updatable = false)
    private Instant creadaEn;

    @PrePersist
    void alPersistir() {
        if (creadaEn == null) {
            creadaEn = Instant.now();
        }
    }
}
