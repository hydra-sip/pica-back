package com.hydra.pica.plataforma_pica.inscripcion.domain;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.common.domain.AuditableEntity;
import com.hydra.pica.plataforma_pica.equipo.domain.Equipo;
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
 * Arco exclusivo: se inscribe un usuario (torneo INDIVIDUAL) o un equipo (torneo por EQUIPO),
 * nunca los dos. Pasa a CONFIRMADA cuando su movimiento de pago queda CONFIRMADO.
 */
@Entity
@Table(name = "inscripcion")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Inscripcion extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "torneo_id", nullable = false)
    private Torneo torneo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "equipo_id")
    private Equipo equipo;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", length = 20, nullable = false)
    private EstadoInscripcion estado = EstadoInscripcion.BORRADOR;

    /** Puesto final en el torneo. */
    @Column(name = "posicion")
    private Integer posicion;

    @Column(name = "reglamento_aceptado_en")
    private Instant reglamentoAceptadoEn;

    public boolean esDeEquipo() {
        return equipo != null;
    }

    /** Quien responde por la inscripción: el participante de la individual, el capitán de la de equipo. */
    public Usuario titular() {
        return esDeEquipo() ? equipo.getCapitan() : usuario;
    }
}
