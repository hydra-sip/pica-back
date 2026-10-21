package com.hydra.pica.plataforma_pica.rifa.domain;

import com.hydra.pica.plataforma_pica.common.domain.AuditableEntity;

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
 * Se crean todos al publicar la rifa, como DISPONIBLE, y la compra los pasa a VENDIDO con un UPDATE
 * condicionado al estado. Sin ticket está DISPONIBLE y con ticket no (lo exige la base).
 */
@Entity
@Table(name = "numero")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Numero extends AuditableEntity {

    @EmbeddedId
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private NumeroId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("rifaId")
    @JoinColumn(name = "rifa_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private Rifa rifa;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id")
    private Ticket ticket;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", length = 20, nullable = false)
    private EstadoNumero estado = EstadoNumero.DISPONIBLE;

    /** Puesto en el sorteo; null si no ganó. */
    @Column(name = "posicion_sorteo")
    private Integer posicionSorteo;

    protected Numero() {
    }

    public Numero(Rifa rifa, int numero) {
        this.rifa = rifa;
        this.id = new NumeroId(rifa.getId(), numero);
    }
}
