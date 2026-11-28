package com.hydra.pica.plataforma_pica.partido.domain;

import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
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
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Un grupo de la fase de grupos (CR-02, V54): A, B, C… dentro del torneo. */
@Entity
@Table(name = "grupo")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Grupo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    @EqualsAndHashCode.Include
    @Setter(AccessLevel.NONE)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "torneo_id", nullable = false)
    private Torneo torneo;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "letra", length = 1, nullable = false)
    private String letra;

    public Grupo(Torneo torneo, String letra) {
        this.torneo = torneo;
        this.letra = letra;
    }
}
