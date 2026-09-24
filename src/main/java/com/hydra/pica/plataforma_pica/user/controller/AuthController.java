package com.hydra.pica.plataforma_pica.user.controller;

import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.dto.IdResponse;
import com.hydra.pica.plataforma_pica.user.dto.ReenviarVerificacionRequest;
import com.hydra.pica.plataforma_pica.user.dto.RegistroRequest;
import com.hydra.pica.plataforma_pica.user.service.DatosPersona;
import com.hydra.pica.plataforma_pica.user.service.NuevoUsuario;
import com.hydra.pica.plataforma_pica.user.service.UsuarioService;
import com.hydra.pica.plataforma_pica.user.service.VerificacionEmailService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UsuarioService usuarioService;
    private final VerificacionEmailService verificacionEmailService;

    public AuthController(UsuarioService usuarioService, VerificacionEmailService verificacionEmailService) {
        this.usuarioService = usuarioService;
        this.verificacionEmailService = verificacionEmailService;
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

    @GetMapping("/verificar")
    public ResponseEntity<Void> verificar(@RequestParam String token) {
        verificacionEmailService.verificar(token);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reenviar-verificacion")
    public ResponseEntity<Void> reenviar(@Valid @RequestBody ReenviarVerificacionRequest request) {
        verificacionEmailService.reenviar(request.email());
        return ResponseEntity.accepted().build();
    }
}
