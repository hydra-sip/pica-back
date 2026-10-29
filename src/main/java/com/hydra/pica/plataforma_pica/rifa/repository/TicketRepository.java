package com.hydra.pica.plataforma_pica.rifa.repository;

import java.util.Optional;

import com.hydra.pica.plataforma_pica.rifa.domain.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    /** La compra que ya se hizo con esa clave (HU-403): repetirla devuelve el mismo ticket. */
    Optional<Ticket> findByClaveIdempotencia(String claveIdempotencia);

    boolean existsByRifaIdAndCompradorId(Long rifaId, Long compradorId);
}

