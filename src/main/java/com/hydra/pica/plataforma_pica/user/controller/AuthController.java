package com.hydra.pica.plataforma_pica.user.controller;

import com.hydra.pica.plataforma_pica.user.domain.Usuario;
import com.hydra.pica.plataforma_pica.user.dto.ExchangeRequest;
import com.hydra.pica.plataforma_pica.user.dto.IdResponse;
import com.hydra.pica.plataforma_pica.user.dto.LoginRequest;
import com.hydra.pica.plataforma_pica.user.dto.ReenviarVerificacionRequest;
import com.hydra.pica.plataforma_pica.user.dto.RegistroRequest;
import com.hydra.pica.plataforma_pica.user.dto.RefreshTokenRequest;
import com.hydra.pica.plataforma_pica.user.dto.TokenPair;
import com.hydra.pica.plataforma_pica.user.service.AuthService;
import com.hydra.pica.plataforma_pica.user.service.DatosPersona;
import com.hydra.pica.plataforma_pica.user.service.NuevoUsuario;
import com.hydra.pica.plataforma_pica.user.service.UsuarioService;
import com.hydra.pica.plataforma_pica.user.service.VerificacionEmailService;

import com.hydra.pica.plataforma_pica.common.security.JwtService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UsuarioService usuarioService;
    private final VerificacionEmailService verificacionEmailService;
    private final AuthService authService;
    private final JwtService jwtService;

    public AuthController(
            UsuarioService usuarioService,
            VerificacionEmailService verificacionEmailService,
            AuthService authService,
            JwtService jwtService) {
        this.usuarioService = usuarioService;
        this.verificacionEmailService = verificacionEmailService;
        this.authService = authService;
        this.jwtService = jwtService;
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

    @PostMapping("/login")
    public TokenPair login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return authService.login(request, httpRequest.getHeader("User-Agent"));
    }

    @PostMapping("/refresh")
    public TokenPair refresh(@Valid @RequestBody RefreshTokenRequest request, HttpServletRequest httpRequest) {
        return authService.refresh(request.refreshToken(), httpRequest.getHeader("User-Agent"));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/public-key", produces = MediaType.TEXT_PLAIN_VALUE)
    public String obtenerClavePublica() {
        return jwtService.obtenerClavePublicaPem();
    }

    @PostMapping("/exchange")
    public TokenPair exchange(@Valid @RequestBody ExchangeRequest request, HttpServletRequest httpRequest) {
        return authService.canjearCodigoOAuth(request.code(), httpRequest.getHeader("User-Agent"));
    }
}
