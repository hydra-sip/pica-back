package com.hydra.pica.plataforma_pica.reputacion.domain;

import java.time.Instant;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

/**
 * Un movimiento del índice de confiabilidad de un organizador (HU-806): qué torneo se canceló, si tuvo gastos y en qué
 * valor quedó el índice.
 */
@Entity
@Table(name = "indice_organizador_evento")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class EventoIndiceOrganizador {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "usuario_id", nullable = false)
    private Long usuarioId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "torneo_id", nullable = false)
    private Torneo torneo;

    @Column(name = "con_gastos", nullable = false)
    private boolean conGastos;

    @Column(name = "delta", nullable = false)
    private int delta;

    @Column(name = "valor_resultante", nullable = false)
    private int valorResultante;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;
}
