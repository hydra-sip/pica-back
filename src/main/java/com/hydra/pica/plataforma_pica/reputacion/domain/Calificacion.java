package com.hydra.pica.plataforma_pica.reputacion.domain;

import java.time.Instant;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
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
 * La calificación que el organizador le pone a un jugador de su torneo terminado (HU-805, T805-1): de 1 a 5, con
 * comentario opcional y la marca de no confiable. Una por jugador y torneo.
 */
@Entity
@Table(name = "calificacion")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Calificacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "torneo_id", nullable = false)
    private Torneo torneo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organizador_id", nullable = false)
    private Usuario organizador;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Column(name = "puntaje", nullable = false)
    private short puntaje;

    @Column(name = "comentario")
    private String comentario;

    @Column(name = "no_confiable", nullable = false)
    private boolean noConfiable;

    @Column(name = "creada_en", nullable = false)
    private Instant creadaEn;
}
