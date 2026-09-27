package com.hydra.pica.plataforma_pica.common.security;

import java.util.List;

public record JwksResponse(
        List<JwkKeyDto> keys
) {}
