package com.hydra.pica.plataforma_pica.common.config;

import com.hydra.pica.plataforma_pica.common.security.JwtAuthenticationFilter;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
// Habilita @PreAuthorize en los controllers; cada endpoint /admin/** declara su permiso (ver x-permiso en el contrato)
@EnableMethodSecurity
public class SecurityConfig {

    private final CorsConfigurationSource corsConfigurationSource;
    private final ObjectMapper objectMapper;

    public SecurityConfig(CorsConfigurationSource corsConfigurationSource, ObjectMapper objectMapper) {
        this.corsConfigurationSource = corsConfigurationSource;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectProvider<JwtAuthenticationFilter> jwtAuthenticationFilterProvider) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .authorizeHttpRequests(authorize -> authorize
                        // Todos los endpoints /auth/** son públicos por contrato; PICA-117 podrá
                        // angostar o quitar este permiso cuando corresponda.
                        .requestMatchers(
                                "/api/v1/health",
                                "/actuator/health",
                                "/api/v1/auth/**",
                                "/oauth2/**",
                                "/login/oauth2/**",
                                "/.well-known/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()));

        jwtAuthenticationFilterProvider.ifAvailable(
                jwtAuthenticationFilter -> {
                    try {
                        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
                    } catch (Exception exception) {
                        throw new IllegalStateException("No se pudo registrar el filtro JWT.", exception);
                    }
                });

        return http.build();
    }

    private AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, exception) -> escribirProblema(
                response,
                HttpStatus.UNAUTHORIZED,
                codigoAutenticacion(request),
                detalleAutenticacion(request));
    }

    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, exception) -> escribirProblema(
                response,
                HttpStatus.FORBIDDEN,
                CodigoError.SIN_PERMISO,
                "No tenés permiso para esta operación");
    }

    private CodigoError codigoAutenticacion(HttpServletRequest request) {
        Object jwtError = request.getAttribute("jwt-error");
        if ("TOKEN_VENCIDO".equals(jwtError)) {
            return CodigoError.TOKEN_VENCIDO;
        }
        if ("TOKEN_INVALIDO".equals(jwtError)) {
            return CodigoError.TOKEN_INVALIDO;
        }
        return CodigoError.NO_AUTENTICADO;
    }

    private String detalleAutenticacion(HttpServletRequest request) {
        return switch (codigoAutenticacion(request)) {
            case TOKEN_VENCIDO -> "El token de acceso venció";
            case TOKEN_INVALIDO -> "El token de acceso no es válido";
            default -> "Hace falta iniciar sesión";
        };
    }

    private void escribirProblema(
            HttpServletResponse response,
            HttpStatus status,
            CodigoError codigo,
            String detalle) throws java.io.IOException {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detalle);
        problemDetail.setProperty("codigo", codigo.name());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problemDetail);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Fuerza 12: lo pide T101-4; 10 (el default) queda corto para 2026
        return new BCryptPasswordEncoder(12);
    }
}
