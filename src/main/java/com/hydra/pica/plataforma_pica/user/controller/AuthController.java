package com.hydra.pica.plataforma_pica.user.controller;

import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.dto.IdResponse;
import com.hydra.pica.plataforma_pica.user.dto.RegistroRequest;
import com.hydra.pica.plataforma_pica.user.service.DatosPersona;
import com.hydra.pica.plataforma_pica.user.service.NuevoUsuario;
import com.hydra.pica.plataforma_pica.user.service.UsuarioService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UsuarioService usuarioService;

    public AuthController(UsuarioService usuarioService) {
        this.usuarioService = usuarioService;
    }

    @PostMapping("/registro")
    public ResponseEntity<IdResponse> registro(@Valid @RequestBody RegistroRequest request) {
        DatosPersona datosPersona = new DatosPersona(
                request.tipoDoc(),
                request.nroDoc(),
                request.nombres(),
                request.apellidos(),
                request.fechaNacimiento(),
                null,
                null);
        Usuario usuario = usuarioService.crear(NuevoUsuario.autoRegistro(
                request.username(),
                request.email(),
                request.password(),
                datosPersona));
        return ResponseEntity.status(HttpStatus.CREATED).body(new IdResponse(usuario.getId()));
    }
}
