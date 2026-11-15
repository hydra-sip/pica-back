package com.hydra.pica.plataforma_pica.partido.domain;

import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Quién quedó en qué grupo (CR-02, V54). {@code torneo_id} está para que la FK compuesta obligue a que el grupo y la
 * inscripción sean del mismo torneo.
 */
@Entity
@Table(name = "grupo_inscripcion")
@Getter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class GrupoInscripcion {

    @EmbeddedId
    @EqualsAndHashCode.Include
    private GrupoInscripcionId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("grupoId")
    @JoinColumn(name = "grupo_id", nullable = false)
    private Grupo grupo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("inscripcionId")
    @JoinColumn(name = "inscripcion_id", nullable = false)
    private Inscripcion inscripcion;

    @Column(name = "torneo_id", nullable = false)
    private Long torneoId;

    public GrupoInscripcion(Grupo grupo, Inscripcion inscripcion) {
        this.id = new GrupoInscripcionId(grupo.getId(), inscripcion.getId());
        this.grupo = grupo;
        this.inscripcion = inscripcion;
        this.torneoId = grupo.getTorneo().getId();
    }
}
