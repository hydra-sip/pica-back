package com.hydra.pica.plataforma_pica.billetera.domain;

import java.math.BigDecimal;

import com.hydra.pica.plataforma_pica.common.domain.AuditableEntity;
import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
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
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * El monto va siempre positivo: el tipo dice si entra o sale. Un movimiento INSCRIPCION lleva su
 * inscripción (la base lo exige).
 */
@Entity
@Table(name = "movimiento")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Movimiento extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "billetera_id", nullable = false)
    private Billetera billetera;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "torneo_id")
    private Torneo torneo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inscripcion_id")
    private Inscripcion inscripcion;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", length = 30, nullable = false)
    private TipoMovimiento tipo;

    @Column(name = "monto", precision = 14, scale = 2, nullable = false)
    private BigDecimal monto;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "moneda", length = 3, nullable = false)
    private String moneda = "ARS";

    @Column(name = "saldo_resultante", precision = 14, scale = 2, nullable = false)
    private BigDecimal saldoResultante;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", length = 20, nullable = false)
    private EstadoMovimiento estado = EstadoMovimiento.PENDIENTE;

    @Column(name = "referencia_externa", length = 100)
    private String referenciaExterna;

    @Column(name = "descripcion", length = 255)
    private String descripcion;
}
