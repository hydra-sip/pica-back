package com.hydra.pica.plataforma_pica.torneo.domain;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import com.hydra.pica.plataforma_pica.user.domain.Usuario;
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

/**
 * Un árbitro designado en un torneo (V20): el árbitro de cada partido tiene que ser uno de ellos, por la FK
 * compuesta de partido. Los permisos son dos columnas, y al menos una va en true.
 */
@Entity
@Table(name = "torneo_arbitro")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ArbitroDesignado {

    @EmbeddedId
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private ArbitroDesignadoId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("torneoId")
    @JoinColumn(name = "torneo_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private Torneo torneo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("usuarioId")
    @JoinColumn(name = "usuario_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private Usuario usuario;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", length = 20, nullable = false)
    private EstadoDesignacion estado = EstadoDesignacion.PENDIENTE;

    @Column(name = "carga_resultados", nullable = false)
    @Setter(AccessLevel.NONE)
    private boolean cargaResultados;

    @Column(name = "resuelve_objeciones", nullable = false)
    @Setter(AccessLevel.NONE)
    private boolean resuelveObjeciones;

    @Column(name = "designado_en", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant designadoEn;

    protected ArbitroDesignado() {
    }

    public ArbitroDesignado(Torneo torneo, Usuario usuario, Set<PermisoArbitro> permisos) {
        this.torneo = torneo;
        this.usuario = usuario;
        this.id = new ArbitroDesignadoId(torneo.getId(), usuario.getId());
        this.designadoEn = Instant.now();
        setPermisos(permisos);
    }

    public Set<PermisoArbitro> getPermisos() {
        Set<PermisoArbitro> permisos = EnumSet.noneOf(PermisoArbitro.class);
        if (cargaResultados) {
            permisos.add(PermisoArbitro.CARGAR_RESULTADOS);
        }
        if (resuelveObjeciones) {
            permisos.add(PermisoArbitro.RESOLVER_OBJECIONES);
        }
        return permisos;
    }

    public void setPermisos(Set<PermisoArbitro> permisos) {
        this.cargaResultados = permisos.contains(PermisoArbitro.CARGAR_RESULTADOS);
        this.resuelveObjeciones = permisos.contains(PermisoArbitro.RESOLVER_OBJECIONES);
    }
}
