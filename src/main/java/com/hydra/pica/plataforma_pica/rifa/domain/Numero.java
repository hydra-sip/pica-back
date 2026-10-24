package com.hydra.pica.plataforma_pica.rifa.domain;

import java.time.Duration;
import java.time.Instant;

import com.hydra.pica.plataforma_pica.common.domain.AuditableEntity;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

/**
 * Un número elegido de una rifa (V13). La fila se inserta al elegirlo y la PK (rifa_id, numero)
 * impide que dos personas se queden con el mismo; un número sin fila está libre.
 *
 * Se reserva ({@link #reservado}) mientras se hace el pago, sin ticket, y pasa a VENDIDO con
 * {@link #vender}. La reserva empieza en {@code creadoEn} y dura lo que diga la aplicación; vencida,
 * se libera borrando la fila. Uno REINTEGRADO conserva la fila y no se vuelve a vender.
 *
 * Es {@link Persistable} para que {@code save} de un número nuevo haga INSERT (persist) y no merge:
 * con la clave ya armada, un merge buscaría la fila y, si otro la eligió antes, la pisaría en vez de
 * fallar por la PK.
 */
@Entity
@Table(name = "numero")
@Getter
@Setter(AccessLevel.NONE)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Numero extends AuditableEntity implements Persistable<NumeroId> {

    @EmbeddedId
    @EqualsAndHashCode.Include
    private NumeroId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("rifaId")
    @JoinColumn(name = "rifa_id", nullable = false)
    private Rifa rifa;

    /** null mientras está RESERVADO. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id")
    private Ticket ticket;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", length = 20, nullable = false)
    @Setter
    private EstadoNumero estado;

    /** Quién lo eligió, solo mientras está RESERVADO; vendido, el comprador es el del ticket. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservado_por")
    private Usuario reservadoPor;

    @Transient
    @Getter(AccessLevel.NONE)
    private boolean nuevo = true;

    protected Numero() {
    }

    private Numero(Rifa rifa, int numero) {
        this.rifa = rifa;
        this.id = new NumeroId(rifa.getId(), numero);
    }

    /** Bloquea el número para {@code usuario} mientras paga. */
    public static Numero reservado(Rifa rifa, int numero, Usuario usuario) {
        Numero reservado = new Numero(rifa, numero);
        reservado.estado = EstadoNumero.RESERVADO;
        reservado.reservadoPor = usuario;
        return reservado;
    }

    /** Vendido de una, sin pasar por la reserva. */
    public static Numero vendido(Rifa rifa, int numero, Ticket ticket) {
        Numero vendido = new Numero(rifa, numero);
        vendido.vender(ticket);
        return vendido;
    }

    /** Se pagó: queda con su ticket, que dice quién lo compró. */
    public void vender(Ticket ticket) {
        this.ticket = ticket;
        this.estado = EstadoNumero.VENDIDO;
        this.reservadoPor = null;
    }

    /** {@code duracion} es cuánto dura una reserva; empieza a contar en {@code creadoEn}. */
    public boolean reservaVencida(Instant ahora, Duration duracion) {
        return estado == EstadoNumero.RESERVADO && !getCreadoEn().plus(duracion).isAfter(ahora);
    }

    @Override
    public boolean isNew() {
        return nuevo;
    }

    @PostLoad
    @PostPersist
    void marcarGuardado() {
        nuevo = false;
    }
}
