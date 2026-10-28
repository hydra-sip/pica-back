package com.hydra.pica.plataforma_pica.rifa.repository;

import com.hydra.pica.plataforma_pica.rifa.domain.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketRepository extends JpaRepository<Ticket, Long> {
    boolean existsByRifaIdAndCompradorId(Long rifaId, Long compradorId);
}

