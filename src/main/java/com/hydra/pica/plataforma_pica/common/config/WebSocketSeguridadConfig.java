package com.hydra.pica.plataforma_pica.common.config;

import java.util.List;

import com.hydra.pica.plataforma_pica.common.security.JwtService;
import com.hydra.pica.plataforma_pica.common.security.VersionesDeSesion;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Quién se suscribe a qué por WebSocket (HU-808). Conectarse sin token sigue permitido: el bracket y las rifas en
 * vivo ({@code /topic/**}) son públicos. Con {@code Authorization: Bearer ...} en el CONNECT, el token se valida como
 * en HTTP (firma y versión de sesión) y la sesión queda a nombre del id del usuario. {@code /user/queue/**} pide
 * sesión: Spring resuelve la cola de cada sesión y la de otro usuario no se alcanza. Suscribirse directo a
 * {@code /queue/**} (el nombre ya resuelto de la cola de una sesión) se rechaza.
 */
@Configuration
public class WebSocketSeguridadConfig implements WebSocketMessageBrokerConfigurer {

    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final VersionesDeSesion versionesDeSesion;

    public WebSocketSeguridadConfig(JwtService jwtService, VersionesDeSesion versionesDeSesion) {
        this.jwtService = jwtService;
        this.versionesDeSesion = versionesDeSesion;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accesor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accesor == null) {
                    return message;
                }
                if (StompCommand.CONNECT.equals(accesor.getCommand())) {
                    autenticar(accesor);
                } else if (StompCommand.SUBSCRIBE.equals(accesor.getCommand())) {
                    exigirPermiso(accesor);
                }
                return message;
            }
        });
    }

    private void autenticar(StompHeaderAccessor accesor) {
        String authorization = accesor.getFirstNativeHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER)) {
            return;
        }
        // un token que no vale corta la conexión: el front renueva el token y vuelve a conectar
        try {
            Claims claims = jwtService.validar(authorization.substring(BEARER.length())).getPayload();
            Integer version = claims.get(JwtService.CLAIM_VERSION_SESION, Integer.class);
            boolean vigente = version != null && versionesDeSesion.versionDeSesion(Long.valueOf(claims.getSubject()))
                    .map(version::equals)
                    .orElse(false);
            if (!vigente) {
                throw new BadCredentialsException("La sesión fue cerrada");
            }
            accesor.setUser(new UsernamePasswordAuthenticationToken(claims.getSubject(), null, List.of()));
        } catch (JwtException e) {
            throw new BadCredentialsException("El token de acceso no es válido", e);
        }
    }

    private void exigirPermiso(StompHeaderAccessor accesor) {
        String destino = accesor.getDestination();
        if (destino == null) {
            return;
        }
        if (destino.startsWith("/queue/")) {
            throw new AccessDeniedException("Las colas se piden por /user/queue/...");
        }
        if (destino.startsWith("/user/") && accesor.getUser() == null) {
            throw new AccessDeniedException("Hace falta iniciar sesión para recibir notificaciones");
        }
    }
}
