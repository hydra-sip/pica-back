package com.hydra.pica.plataforma_pica.user.domain;

import java.time.Instant;
import java.time.LocalDate;

import com.hydra.pica.plataforma_pica.common.domain.AuditableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "persona")
@SQLRestriction("eliminado_en IS NULL")
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Persona extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "nombres", length = 100, nullable = false)
    private String nombres;

    @Column(name = "apellidos", length = 100, nullable = false)
    private String apellidos;

    @Column(name = "tipo_doc", length = 20)
    private String tipoDoc;

    @Column(name = "nro_doc", length = 30)
    private String nroDoc;

    @Column(name = "fecha_nacimiento")
    private LocalDate fechaNacimiento;

    @Column(name = "domicilio_postal", length = 255)
    private String domicilioPostal;

    @Column(name = "telefono", length = 30)
    private String telefono;

    @Column(name = "descripcion", columnDefinition = "TEXT")
    private String descripcion;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", length = 20, nullable = false)
    private EstadoGeneral estado;

    @Column(name = "eliminado_en")
    private Instant eliminadoEn;

    @Override
    public String toString() {
        return "Persona{"
                + "id=" + id
                + ", nombres='" + nombres + '\''
                + ", apellidos='" + apellidos + '\''
                + ", tipoDoc='" + tipoDoc + '\''
                + ", nroDoc='" + nroDoc + '\''
                + ", fechaNacimiento=" + fechaNacimiento
                + ", domicilioPostal='" + domicilioPostal + '\''
                + ", telefono='" + telefono + '\''
                + ", descripcion='" + descripcion + '\''
                + ", estado=" + estado
                + ", eliminadoEn=" + eliminadoEn
                + '}';
    }
}
