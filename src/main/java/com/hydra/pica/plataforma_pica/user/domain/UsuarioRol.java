package com.hydra.pica.plataforma_pica.user.domain;

import java.time.Instant;

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
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "usuario_rol")
@SQLRestriction("eliminado_en IS NULL")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class UsuarioRol {

    @EmbeddedId
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private UsuarioRolId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("usuarioId")
    @JoinColumn(name = "usuario_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private Usuario usuario;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("rolId")
    @JoinColumn(name = "rol_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private Rol rol;

    @Column(name = "asignado_en", nullable = false, updatable = false)
    private Instant asignadoEn;

    @Column(name = "asignado_por", length = 100)
    private String asignadoPor;

    @Column(name = "eliminado_en")
    private Instant eliminadoEn;

    @Column(name = "eliminado_por", length = 100)
    private String eliminadoPor;

    protected UsuarioRol() {
    }

    public UsuarioRol(Usuario usuario, Rol rol) {
        if (usuario == null || rol == null) {
            throw new IllegalArgumentException("Usuario y rol no pueden ser null");
        }
        if (usuario.getId() == null || rol.getId() == null) {
            throw new IllegalArgumentException("Usuario y rol deben estar persistidos y tener un id");
        }

        this.usuario = usuario;
        this.rol = rol;
        this.id = new UsuarioRolId(usuario.getId(), rol.getId());
    }

    @Override
    public String toString() {
        return "UsuarioRol{"
                + "id=" + id
                + ", asignadoEn=" + asignadoEn
                + ", asignadoPor='" + asignadoPor + '\''
                + ", eliminadoEn=" + eliminadoEn
                + ", eliminadoPor='" + eliminadoPor + '\''
                + '}';
    }
}
