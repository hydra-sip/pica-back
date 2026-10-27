package com.hydra.pica.plataforma_pica.inscripcion.domain;

import com.hydra.pica.plataforma_pica.common.domain.AuditableEntity;
import com.hydra.pica.plataforma_pica.equipo.domain.Equipo;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "reemplazo")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reemplazo extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inscripcion_id", nullable = false)
    private Inscripcion inscripcion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "equipo_id", nullable = false)
    private Equipo equipo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sale_usuario_id", nullable = false)
    private Usuario sale;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entra_usuario_id", nullable = false)
    private Usuario entra;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "realizado_por_usuario_id", nullable = false)
    private Usuario realizadoPor;

    public Reemplazo(Inscripcion inscripcion, Equipo equipo, Usuario sale, Usuario entra, Usuario realizadoPor) {
        this.inscripcion = inscripcion;
        this.equipo = equipo;
        this.sale = sale;
        this.entra = entra;
        this.realizadoPor = realizadoPor;
    }
}
