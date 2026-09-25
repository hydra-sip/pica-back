package com.hydra.pica.plataforma_pica.user.service;

import com.hydra.pica.plataforma_pica.common.config.AdminConfig.AdminProperties;
import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Crea el Admin del sistema al arrancar si no existe (LBR_001: usuario Admin con rol Super Usuario).
 * Username y email salen de {@code app.admin}; la contraseña, de ADMIN_INITIAL_PASSWORD.
 *
 * Solo actúa la primera vez: si el usuario ya existe, aunque esté dado de baja, no lo toca, así una
 * contraseña cambiada después no vuelve a la inicial en el próximo arranque. Sin la variable no
 * frena el arranque: avisa en el log y la app sigue sin Admin.
 */
@Component
@RequiredArgsConstructor
public class AdminInicial implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInicial.class);

    private final AdminProperties admin;
    private final UsuarioRepository usuarioRepository;
    private final UsuarioService usuarioService;

    @Override
    public void run(ApplicationArguments args) {
        if (usuarioRepository.existsByUsernameIncluyendoEliminados(admin.username())) {
            return;
        }
        if (admin.initialPassword() == null || admin.initialPassword().isBlank()) {
            log.warn("No existe el usuario {} y falta ADMIN_INITIAL_PASSWORD: no se crea el Admin del sistema",
                    admin.username());
            return;
        }
        Usuario creado = usuarioService.crear(
                NuevoUsuario.adminDelSistema(admin.username(), admin.email(), admin.initialPassword()));
        log.info("Se creó el Admin del sistema {} (id {})", creado.getUsername(), creado.getId());
    }
}
