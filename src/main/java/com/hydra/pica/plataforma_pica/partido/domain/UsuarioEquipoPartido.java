package com.hydra.pica.plataforma_pica.partido.domain;

import com.hydra.pica.plataforma_pica.common.domain.AuditableEntity;
import com.hydra.pica.plataforma_pica.equipo.domain.Equipo;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;

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

/**
 * Qué integrantes de un equipo jugaron el partido (la agregación del DER). La base exige que el
 * usuario sea integrante de ese equipo (FK compuesta a usuario_equipo).
 */
@Entity
@Table(name = "usuario_equipo_partido")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class UsuarioEquipoPartido extends AuditableEntity {

    @EmbeddedId
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private UsuarioEquipoPartidoId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("partidoId")
    @JoinColumn(name = "partido_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private Partido partido;

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

    protected UsuarioEquipoPartido() {
    }

    public UsuarioEquipoPartido(Partido partido, Usuario usuario, Equipo equipo) {
        this.partido = partido;
        this.usuario = usuario;
        this.equipo = equipo;
        this.id = new UsuarioEquipoPartidoId(partido.getId(), usuario.getId(), equipo.getId());
    }
}
