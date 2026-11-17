package com.hydra.pica.plataforma_pica.reputacion.domain;

import java.time.Instant;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
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
 * Una sanción a un jugador de un torneo (HU-803, T803-1): quién la aplicó, por qué y con qué gravedad. El partido es
 * opcional. No se borra: si se anula, queda con {@code anuladaEn}.
 */
@Entity
@Table(name = "sancion")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Sancion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "torneo_id", nullable = false)
    private Torneo torneo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "partido_id")
    private Partido partido;

    @Enumerated(EnumType.STRING)
    @Column(name = "gravedad", length = 10, nullable = false)
    private Gravedad gravedad;

    @Column(name = "motivo", nullable = false)
    private String motivo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "aplicada_por", nullable = false)
    private Usuario aplicadaPor;

    @Column(name = "aplicada_en", nullable = false)
    private Instant aplicadaEn;

    @Column(name = "anulada_en")
    private Instant anuladaEn;
}
