package com.hydra.pica.plataforma_pica.equipo.domain;

import com.hydra.pica.plataforma_pica.common.domain.AuditableEntity;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Un integrante del equipo que juega un torneo, por inscripción. Las FK compuestas de la base aseguran que es
 * integrante del equipo y que el equipo es el de la inscripción. No se toca cuando el integrante se da de baja
 * del equipo: la nómina ya inscripta no cambia.
 */
@Entity
@Table(name = "nomina")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Nomina extends AuditableEntity {

    @EmbeddedId
    @EqualsAndHashCode.Include
    private NominaId id;

    // Solo lectura: usuario_id es parte de la clave y de dos FK compuestas (a usuario_equipo y, con equipo_id, a
    // la inscripción), que @MapsId no sabe expresar. La FK compuesta de la base asegura que es un integrante.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", insertable = false, updatable = false)
    private Usuario usuario;

    public Nomina(Long usuarioId, Long equipoId, Long inscripcionId) {
        this.id = new NominaId(usuarioId, equipoId, inscripcionId);
    }
}
