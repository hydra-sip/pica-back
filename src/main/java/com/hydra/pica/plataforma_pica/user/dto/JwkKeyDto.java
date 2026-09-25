package com.hydra.pica.plataforma_pica.user.dto;

public record JwkKeyDto(
        String kty,
        String use,
        String alg,
        String n,
        String e
) {}
