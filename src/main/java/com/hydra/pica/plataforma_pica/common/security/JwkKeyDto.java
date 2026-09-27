package com.hydra.pica.plataforma_pica.common.security;

public record JwkKeyDto(
        String kty,
        String use,
        String alg,
        String kid,
        String n,
        String e
) {}
