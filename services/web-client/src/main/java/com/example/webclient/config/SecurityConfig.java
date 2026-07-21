package com.example.webclient.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    /**
     * This app is an OAuth2 *client*: it initiates login (Authorization Code + PKCE)
     * and holds a server-side session. Contrast with product-service, which is a
     * *resource server* that only validates bearer tokens.
     */
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    ClientRegistrationRepository clients) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/error").permitAll()
                .anyRequest().authenticated()          // any other page triggers login
            )
            // Triggers the whole redirect dance; Spring uses PKCE because the
            // client is registered as public (client-authentication-method: none).
            .oauth2Login(login -> login.defaultSuccessUrl("/me", true))
            // RP-initiated logout: also end the Keycloak SSO session, then return home.
            .logout(logout -> logout.logoutSuccessHandler(oidcLogoutSuccessHandler(clients)));
        return http.build();
    }

    private OidcClientInitiatedLogoutSuccessHandler oidcLogoutSuccessHandler(
            ClientRegistrationRepository clients) {
        OidcClientInitiatedLogoutSuccessHandler handler =
                new OidcClientInitiatedLogoutSuccessHandler(clients);
        handler.setPostLogoutRedirectUri("{baseUrl}/");
        return handler;
    }
}
