package com.hydra.pica.plataforma_pica.billetera.domain;

import java.math.BigDecimal;
import java.math.BigInteger;

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
 * Una carga de saldo que el usuario paga con su wallet en el contrato PasarelaCargas (HU-609, PICA-333). La
 * {@code referencia} es el {@code bytes32} que se paga en el contrato, y {@code montoWei} lo que hay que mandar,
 * fijado con la cotización al iniciarla. El hash, el pagador y el movimiento llegan al acreditarse.
 */
@Entity
@Table(name = "carga_cripto")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class CargaCripto extends AuditableEntity {

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

    @Column(name = "monto_pesos", precision = 14, scale = 2, nullable = false, updatable = false)
    private BigDecimal montoPesos;

    @Column(name = "monto_wei", precision = 78, scale = 0, nullable = false, updatable = false)
    private BigInteger montoWei;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", length = 20, nullable = false)
    private EstadoCargaCripto estado = EstadoCargaCripto.PENDIENTE;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "hash", length = 66)
    private String hash;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "pagador", length = 42)
    private String pagador;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movimiento_id")
    private Movimiento movimiento;
}
