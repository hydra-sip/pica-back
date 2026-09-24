package com.hydra.pica.plataforma_pica.common.config;

import com.hydra.pica.plataforma_pica.common.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
// Habilita @PreAuthorize en los controllers; cada endpoint /admin/** declara su permiso (ver x-permiso en el contrato)
@EnableMethodSecurity
public class SecurityConfig {

    private final CorsConfigurationSource corsConfigurationSource;

    public SecurityConfig(CorsConfigurationSource corsConfigurationSource) {
        this.corsConfigurationSource = corsConfigurationSource;
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
                        .requestMatchers("/api/v1/health", "/actuator/health", "/api/v1/auth/**").permitAll()
                        .anyRequest().authenticated())
                // Sin token es 401, no 403 (el 403 queda para "autenticado pero sin permiso").
                // PICA-117 lo reemplaza por el entry point del JWT que además escribe el ProblemDetail.
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));

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

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Fuerza 12: lo pide T101-4; 10 (el default) queda corto para 2026
        return new BCryptPasswordEncoder(12);
    }
}
