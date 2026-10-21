package com.hydra.pica.plataforma_pica.billetera.domain;

import java.math.BigDecimal;

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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Una por usuario. Hasta que llegue PICA-6 la usa solo el cobro simulado. */
@Entity
@Table(name = "billetera")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Billetera extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false, unique = true)
    private Usuario usuario;

    @Column(name = "saldo_disponible", precision = 14, scale = 2, nullable = false)
    private BigDecimal saldoDisponible = BigDecimal.ZERO;

    @Column(name = "saldo_retenido", precision = 14, scale = 2, nullable = false)
    private BigDecimal saldoRetenido = BigDecimal.ZERO;

    @Column(name = "ganancia_acumulada", precision = 14, scale = 2, nullable = false)
    private BigDecimal gananciaAcumulada = BigDecimal.ZERO;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "moneda", length = 3, nullable = false)
    private String moneda = "ARS";

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", length = 20, nullable = false)
    private EstadoBilletera estado = EstadoBilletera.ACTIVA;
}
