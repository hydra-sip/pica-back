package com.hydra.pica.plataforma_pica.torneo.domain;

import java.math.BigDecimal;

import com.hydra.pica.plataforma_pica.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * Un gasto que el organizador declaró al cancelar (HU-605), con la clave de su comprobante en el almacenamiento. No son
 * los gastos previstos del torneo (HU-601): estos esperan la revisión de soporte.
 */
@Entity
@Table(name = "gasto_cancelacion")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class GastoCancelacion extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cancelacion_id", nullable = false)
    private Cancelacion cancelacion;

    @Column(name = "monto", precision = 14, scale = 2, nullable = false)
    private BigDecimal monto;

    @Column(name = "concepto", nullable = false)
    private String concepto;

    @Column(name = "comprobante", length = 500, nullable = false)
    private String comprobante;

    @Column(name = "tipo_contenido", length = 50, nullable = false)
    private String tipoContenido;
}
