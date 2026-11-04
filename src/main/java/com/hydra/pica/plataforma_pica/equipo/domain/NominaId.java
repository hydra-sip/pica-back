package com.hydra.pica.plataforma_pica.equipo.domain;

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
public class NominaId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "usuario_id", nullable = false)
    private Long usuarioId;

    @Column(name = "equipo_id", nullable = false)
    private Long equipoId;

    @Column(name = "inscripcion_id", nullable = false)
    private Long inscripcionId;
}
