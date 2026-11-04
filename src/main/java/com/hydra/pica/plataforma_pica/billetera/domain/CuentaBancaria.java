package com.hydra.pica.plataforma_pica.billetera.domain;

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
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

/** Solo se retira a una cuenta VALIDADA. */
@Entity
@Table(name = "cuenta_bancaria")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class CuentaBancaria extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", length = 10, nullable = false)
    private TipoCuenta tipo;

    /** El CBU, el CVU o el alias, según el tipo. */
    @Column(name = "valor", length = 30, nullable = false)
    private String valor;

    @Column(name = "titular", length = 150, nullable = false)
    private String titular;

    @Column(name = "cuit_cuil", length = 13, nullable = false)
    private String cuitCuil;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_validacion", length = 20, nullable = false)
    private EstadoValidacion estadoValidacion = EstadoValidacion.PENDIENTE;

    @Column(name = "validada_en")
    private Instant validadaEn;

    @Column(name = "eliminado_en")
    private Instant eliminadoEn;
}
