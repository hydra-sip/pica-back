package com.hydra.pica.plataforma_pica.reputacion.domain;

import java.time.Instant;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Un movimiento del índice de fair play (HU-802): qué lo movió, cuánto (ya con el tope y el piso aplicados) y en qué
 * quedó. El historial del perfil.
 */
@Entity
@Table(name = "fair_play_evento")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class EventoFairPlay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "usuario_id", nullable = false)
    private Long usuarioId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", length = 10, nullable = false)
    private TipoEventoFairPlay tipo;

    @Column(name = "delta", nullable = false)
    private int delta;

    @Column(name = "valor_resultante", nullable = false)
    private int valorResultante;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "torneo_id", nullable = false)
    private Torneo torneo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "partido_id")
    private Partido partido;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sancion_id")
    private Sancion sancion;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;
}
