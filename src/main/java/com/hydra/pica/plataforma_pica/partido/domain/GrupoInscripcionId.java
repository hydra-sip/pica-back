package com.hydra.pica.plataforma_pica.partido.domain;

import java.io.Serializable;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class GrupoInscripcionId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "grupo_id", nullable = false)
    private Long grupoId;

    @Column(name = "inscripcion_id", nullable = false)
    private Long inscripcionId;
}
