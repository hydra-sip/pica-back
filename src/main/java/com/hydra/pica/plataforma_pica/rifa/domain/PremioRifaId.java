package com.hydra.pica.plataforma_pica.rifa.domain;

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
public class PremioRifaId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "rifa_id", nullable = false)
    private Long rifaId;

    @Column(name = "puesto", nullable = false)
    private Integer puesto;
}
