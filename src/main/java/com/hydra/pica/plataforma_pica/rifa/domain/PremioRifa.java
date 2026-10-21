package com.hydra.pica.plataforma_pica.rifa.domain;

import java.math.BigDecimal;

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

/** % del pozo de la rifa para un puesto. Que sumen 100 lo valida la aplicación al publicar. */
@Entity
@Table(name = "premio_rifa")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class PremioRifa {

    @EmbeddedId
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private PremioRifaId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("rifaId")
    @JoinColumn(name = "rifa_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private Rifa rifa;

    @Column(name = "pct", precision = 5, scale = 2, nullable = false)
    private BigDecimal pct;

    @Column(name = "descripcion", length = 255)
    private String descripcion;

    protected PremioRifa() {
    }

    public PremioRifa(Rifa rifa, int puesto) {
        this.rifa = rifa;
        this.id = new PremioRifaId(rifa.getId(), puesto);
    }
}
