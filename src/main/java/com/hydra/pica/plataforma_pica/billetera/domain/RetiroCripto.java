package com.hydra.pica.plataforma_pica.billetera.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;

import com.hydra.pica.plataforma_pica.common.domain.AuditableEntity;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Un retiro de saldo a la wallet (HU-610, PICA-455): el permiso que firmó el back para el contrato PasarelaRetiros
 * ({@code referencia}, {@code destino}, {@code montoWei} y {@code venceEn}) y el movimiento RETIRO que descontó el
 * saldo. El hash llega cuando el usuario avisa que lo cobró.
 */
@Entity
@Table(name = "retiro_cripto")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class RetiroCripto extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "referencia", length = 66, nullable = false, unique = true, updatable = false)
    private String referencia;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false, updatable = false)
    private Usuario usuario;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "destino", length = 42, nullable = false, updatable = false)
    private String destino;

    @Column(name = "monto_pesos", precision = 14, scale = 2, nullable = false, updatable = false)
    private BigDecimal montoPesos;

    @Column(name = "monto_wei", precision = 78, scale = 0, nullable = false, updatable = false)
    private BigInteger montoWei;

    @Column(name = "vence_en", nullable = false, updatable = false)
    private Instant venceEn;

    @Column(name = "firma", length = 132, nullable = false, updatable = false)
    private String firma;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", length = 20, nullable = false)
    private EstadoRetiroCripto estado = EstadoRetiroCripto.PENDIENTE;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "hash", length = 66)
    private String hash;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "movimiento_id", nullable = false, updatable = false)
    private Movimiento movimiento;
}
