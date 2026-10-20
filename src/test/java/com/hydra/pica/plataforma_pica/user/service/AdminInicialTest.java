package com.hydra.pica.plataforma_pica.user.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hydra.pica.plataforma_pica.common.config.AdminConfig.AdminProperties;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class AdminInicialTest {

    private final UsuarioRepository usuarioRepository = mock(UsuarioRepository.class);
    private final UsuarioService usuarioService = mock(UsuarioService.class);

    @Test
    void sinAdminInitialPasswordNoCreaNadaYLaAppArrancaIgual() {
        when(usuarioRepository.existsByUsernameIncluyendoEliminados("admin")).thenReturn(false);

        inicial("").run(new DefaultApplicationArguments());
        inicial(null).run(new DefaultApplicationArguments());

        verifyNoInteractions(usuarioService);
    }

    @Test
    void siExisteAunqueEsteDadoDeBajaNoLoVuelveACrear() {
        // la consulta es nativa e incluye eliminados: un Admin dado de baja no se reemplaza por otro
        when(usuarioRepository.existsByUsernameIncluyendoEliminados("admin")).thenReturn(true);

        inicial("inicial").run(new DefaultApplicationArguments());

        verifyNoInteractions(usuarioService);
    }

    private AdminInicial inicial(String password) {
        return new AdminInicial(new AdminProperties("admin", "admin@pica.local", password), usuarioRepository,
                usuarioService);
    }
}
