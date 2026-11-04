package com.hydra.pica.plataforma_pica.rifa.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import com.hydra.pica.plataforma_pica.rifa.domain.EstadoNumero;
import com.hydra.pica.plataforma_pica.rifa.domain.Numero;
import com.hydra.pica.plataforma_pica.rifa.domain.NumeroId;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NumeroRepository extends JpaRepository<Numero, NumeroId> {

    long countByIdRifaIdAndEstado(Long rifaId, EstadoNumero estado);

    List<Numero> findByIdRifaIdAndEstado(Long rifaId, EstadoNumero estado);

    List<Numero> findByIdRifaIdAndIdNumeroIn(Long rifaId, Collection<Integer> numeros);

    /** Todos los que tienen fila (vendidos, reservados y reintegrados), en orden: los demás están libres. */
    List<Numero> findByIdRifaIdOrderByIdNumero(Long rifaId);

    /** Los de un ticket, para devolver el mismo comprobante a una compra repetida (HU-403). */
    List<Numero> findByTicketIdOrderByIdNumero(Long ticketId);

    /**
     * Los números elegidos bloqueados hasta el fin de la transacción, para que dos compras de los mismos números no se
     * pisen (HU-403). En orden, así dos pedidos los bloquean igual y no se traban entre sí.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT n FROM Numero n WHERE n.id.rifaId = :rifaId AND n.id.numero IN :numeros ORDER BY n.id.numero")
    List<Numero> bloquear(@Param("rifaId") Long rifaId, @Param("numeros") Collection<Integer> numeros);

    /** Libera las reservas vencidas de esos números: una reserva que pasó su duración cuenta como libre. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM Numero n WHERE n.id.rifaId = :rifaId AND n.id.numero IN :numeros"
            + " AND n.estado = com.hydra.pica.plataforma_pica.rifa.domain.EstadoNumero.RESERVADO"
            + " AND n.creadoEn <= :vencidasDesde")
    int liberarVencidas(@Param("rifaId") Long rifaId, @Param("numeros") Collection<Integer> numeros,
            @Param("vencidasDesde") Instant vencidasDesde);

    /** Las reservas hechas después de {@code desde}: las vigentes, si {@code desde} es ahora menos la duración. */
    long countByIdRifaIdAndEstadoAndCreadoEnAfter(Long rifaId, EstadoNumero estado, Instant desde);

    /** Todas las reservas de la rifa, al volverla a borrador o eliminarla (PICA-287). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM Numero n WHERE n.id.rifaId = :rifaId"
            + " AND n.estado = com.hydra.pica.plataforma_pica.rifa.domain.EstadoNumero.RESERVADO")
    int borrarReservas(@Param("rifaId") Long rifaId);

    /** Las reservas del usuario en la rifa, vigentes o no: reservar otra vez o cancelar las reemplaza. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM Numero n WHERE n.id.rifaId = :rifaId AND n.reservadoPor.id = :usuarioId"
            + " AND n.estado = com.hydra.pica.plataforma_pica.rifa.domain.EstadoNumero.RESERVADO")
    int liberarDelUsuario(@Param("rifaId") Long rifaId, @Param("usuarioId") Long usuarioId);
}
