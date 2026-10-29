package com.hydra.pica.plataforma_pica.rifa.controller;

import java.util.List;

import com.hydra.pica.plataforma_pica.common.security.CurrentUserProvider;
import com.hydra.pica.plataforma_pica.rifa.dto.TicketDetalle;
import com.hydra.pica.plataforma_pica.rifa.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tickets de rifa (HU-404, PICA-224): los del usuario y el detalle de uno. Cualquier usuario con sesión; quién ve
 * cada ticket lo controla {@link TicketService}.
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@RequestMapping("/api/v1/tickets")
public class TicketController {

    private final TicketService ticketService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/mis-tickets")
    public List<TicketDetalle> misTickets() {
        return ticketService.misTickets(usuarioActual());
    }

    @GetMapping("/{id}")
    public TicketDetalle ver(@PathVariable Long id) {
        return ticketService.detalle(usuarioActual(), id);
    }

    private Long usuarioActual() {
        return currentUserProvider.getCurrentUserId()
                .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin usuario en la sesión"));
    }
}
