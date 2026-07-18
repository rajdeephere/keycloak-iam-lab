package com.example.productservice.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Defense in depth: Spring's default JWT validation checks signature, issuer,
 * and expiry -- but NOT audience. Without this, a token minted for a different
 * client (e.g. aud="account") would still be accepted here.
 *
 * This validator rejects any token whose "aud" claim does not include this
 * service's expected audience. Keycloak stamps aud=product-service via an
 * audience protocol mapper on the calling clients.
 */
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final String expectedAudience;

    public AudienceValidator(String expectedAudience) {
        this.expectedAudience = expectedAudience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (jwt.getAudience() != null && jwt.getAudience().contains(expectedAudience)) {
            return OAuth2TokenValidatorResult.success();
        }
        OAuth2Error error = new OAuth2Error(
                "invalid_token",
                "The required audience '" + expectedAudience + "' is missing from the token",
                null);
        return OAuth2TokenValidatorResult.failure(error);
    }
}
