package com.hydra.pica.plataforma_pica.user.dto;

public record TokenPair(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn) {
}
