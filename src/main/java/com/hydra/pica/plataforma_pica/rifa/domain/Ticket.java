package com.hydra.pica.plataforma_pica.rifa.domain;

import java.math.BigDecimal;
import java.time.Instant;

import com.hydra.pica.plataforma_pica.billetera.domain.Movimiento;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Una compra de uno o más números, con su movimiento de pago. La clave de idempotencia es única:
 * un reintento de la misma compra no cobra dos veces.
 */
@Entity
@Table(name = "ticket")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "codigo", length = 20, nullable = false, unique = true)
    private String codigo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rifa_id", nullable = false)
    private Rifa rifa;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "comprador_id", nullable = false)
    private Usuario comprador;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "movimiento_id", nullable = false, unique = true)
    private Movimiento movimiento;

    @Column(name = "monto", precision = 14, scale = 2, nullable = false)
    private BigDecimal monto;

    @Column(name = "comision", precision = 14, scale = 2, nullable = false)
    private BigDecimal comision;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "moneda", length = 3, nullable = false)
    private String moneda = "ARS";

    @Column(name = "clave_idempotencia", length = 64, nullable = false, unique = true)
    private String claveIdempotencia;

    @CreatedDate
    @Column(name = "creado_en", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant creadoEn;

    @CreatedBy
    @Column(name = "creado_por", length = 100, updatable = false)
    @Setter(AccessLevel.NONE)
    private String creadoPor;
}
