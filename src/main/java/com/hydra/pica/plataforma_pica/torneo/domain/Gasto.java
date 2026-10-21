package com.hydra.pica.plataforma_pica.torneo.domain;

import java.math.BigDecimal;
import java.time.Instant;

import com.hydra.pica.plataforma_pica.user.domain.Usuario;

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

/** Entidad débil de torneo: {@code gastoId} numera los gastos dentro de cada torneo. */
@Entity
@Table(name = "gasto")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Gasto {

    @EmbeddedId
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private GastoId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("torneoId")
    @JoinColumn(name = "torneo_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private Torneo torneo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "beneficiario_id", nullable = false)
    private Usuario beneficiario;

    @Column(name = "descripcion", length = 255)
    private String descripcion;

    @Column(name = "monto", precision = 14, scale = 2)
    private BigDecimal monto;

    @Column(name = "monto_abonado", precision = 14, scale = 2, nullable = false)
    private BigDecimal montoAbonado = BigDecimal.ZERO;

    @Column(name = "fecha_abonado")
    private Instant fechaAbonado;

    @Column(name = "comprobante", length = 500, nullable = false)
    private String comprobante;

    protected Gasto() {
    }

    public Gasto(Torneo torneo, int gastoId) {
        this.torneo = torneo;
        this.id = new GastoId(torneo.getId(), gastoId);
    }
}
