package com.hydra.pica.plataforma_pica.torneo.domain;

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

/** % del pozo para un puesto. Que los puestos sumen 100 lo valida la aplicación al publicar. */
@Entity
@Table(name = "premio")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Premio {

    @EmbeddedId
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private PremioId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("torneoId")
    @JoinColumn(name = "torneo_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private Torneo torneo;

    @Column(name = "pct", precision = 5, scale = 2, nullable = false)
    private BigDecimal pct;

    protected Premio() {
    }

    public Premio(Torneo torneo, int puesto) {
        this.torneo = torneo;
        this.id = new PremioId(torneo.getId(), puesto);
    }
}
