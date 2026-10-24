package com.hydra.pica.plataforma_pica.rifa.domain;

import java.math.BigDecimal;
import java.time.Instant;

import com.hydra.pica.plataforma_pica.common.domain.AuditableEntity;
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
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Un torneo tiene como máximo una rifa. {@code listaHash} sella los vendidos al cerrar la venta;
 * compromiso y semilla son del sorteo verificable (PICA-5). Baja lógica sin {@code @SQLRestriction},
 * como Torneo: cada consulta filtra los eliminados. En BORRADOR la ventana de venta y los términos
 * pueden estar vacíos; fuera de BORRADOR los exige ck_rifa_completa_fuera_de_borrador (V10).
 */
@Entity
@Table(name = "rifa")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Rifa extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organizador_id", nullable = false)
    private Usuario organizador;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "torneo_id", unique = true)
    private Torneo torneo;

    @Column(name = "titulo", length = 100, nullable = false)
    private String titulo;

    @Column(name = "descripcion", columnDefinition = "TEXT")
    private String descripcion;

    @Column(name = "numero_desde", nullable = false)
    private Integer numeroDesde;

    @Column(name = "numero_hasta", nullable = false)
    private Integer numeroHasta;

    @Column(name = "precio_numero", precision = 14, scale = 2, nullable = false)
    private BigDecimal precioNumero;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "moneda", length = 3, nullable = false)
    private String moneda = "ARS";

    @Column(name = "venta_desde")
    private Instant ventaDesde;

    @Column(name = "venta_hasta")
    private Instant ventaHasta;

    @Column(name = "terminos_aceptados_en")
    private Instant terminosAceptadosEn;

    @Column(name = "loteria_declarada_en")
    private Instant loteriaDeclaradaEn;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", length = 30, nullable = false)
    private EstadoRifa estado = EstadoRifa.BORRADOR;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "lista_hash", length = 64)
    private String listaHash;

    @Column(name = "lista_sellada_en")
    private Instant listaSelladaEn;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "compromiso_hash", length = 64)
    private String compromisoHash;

    @Column(name = "compromiso_publicado_en")
    private Instant compromisoPublicadoEn;

    @Column(name = "semilla", length = 255)
    private String semilla;

    @Column(name = "semilla_firma", columnDefinition = "TEXT")
    private String semillaFirma;

    @Column(name = "semilla_obtenida_en")
    private Instant semillaObtenidaEn;

    @Column(name = "sorteada_en")
    private Instant sorteadaEn;

    @Column(name = "eliminado_en")
    private Instant eliminadoEn;
}
