package com.hydra.pica.plataforma_pica.equipo.domain;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.common.domain.AuditableEntity;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

/** Integrante de un equipo. La baja lógica conserva el par, como usuario_rol. */
@Entity
@Table(name = "usuario_equipo")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class UsuarioEquipo extends AuditableEntity {

    @EmbeddedId
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private UsuarioEquipoId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("usuarioId")
    @JoinColumn(name = "usuario_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private Usuario usuario;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("equipoId")
    @JoinColumn(name = "equipo_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private Equipo equipo;

    @Column(name = "eliminado_en")
    private Instant eliminadoEn;

    protected UsuarioEquipo() {
    }

    public UsuarioEquipo(Usuario usuario, Equipo equipo) {
        this.usuario = usuario;
        this.equipo = equipo;
        this.id = new UsuarioEquipoId(usuario.getId(), equipo.getId());
    }
}
