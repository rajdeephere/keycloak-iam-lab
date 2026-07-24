package com.example.adminservice.keycloak;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper over the Keycloak Admin REST API ({base}/admin/realms/{realm}/...).
 * Every call carries a bearer token obtained via client_credentials for the
 * "admin-service" client, whose service account holds realm-management roles.
 *
 * (The official org.keycloak:keycloak-admin-client library does the same thing with
 * a typed API; calling the REST endpoints directly keeps the mechanics visible.)
 */
@Component
public class KeycloakAdminClient {

    private final RestClient rest;
    private final OAuth2AuthorizedClientManager clientManager;
    private final String realmPath;

    public KeycloakAdminClient(@Value("${keycloak.base-url}") String baseUrl,
                               @Value("${keycloak.realm}") String realm,
                               OAuth2AuthorizedClientManager clientManager) {
        this.rest = RestClient.builder().baseUrl(baseUrl).build();
        this.realmPath = "/admin/realms/" + realm;
        this.clientManager = clientManager;
    }

    /** Acquire (cached) admin-service token via client_credentials. */
    private String bearer() {
        return clientManager.authorize(OAuth2AuthorizeRequest
                .withClientRegistrationId("admin-service").principal("admin-service").build())
                .getAccessToken().getTokenValue();
    }

    // --- JOINER: provision a user, return the new user id ---
    public String createUser(String username, String email, String firstName, String lastName) {
        URI location = rest.post().uri(realmPath + "/users")
                .header("Authorization", "Bearer " + bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "username", username,
                        "email", email,
                        "firstName", firstName,
                        "lastName", lastName,
                        "enabled", true,
                        "emailVerified", true))
                .retrieve().toBodilessEntity()
                .getHeaders().getLocation();
        return location == null ? null
                : location.getPath().substring(location.getPath().lastIndexOf('/') + 1);
    }

    public List<Map<String, Object>> listUsers(String search) {
        return rest.get().uri(uri -> uri.path(realmPath + "/users")
                        .queryParam("search", search == null ? "" : search).build())
                .header("Authorization", "Bearer " + bearer())
                .retrieve().body(new ParameterizedTypeReference<>() {});
    }

    public void setPassword(String userId, String password) {
        rest.put().uri(realmPath + "/users/" + userId + "/reset-password")
                .header("Authorization", "Bearer " + bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("type", "password", "value", password, "temporary", false))
                .retrieve().toBodilessEntity();
    }

    // --- MOVER: change access by assigning/removing realm roles ---
    public void assignRealmRole(String userId, String roleName) {
        rest.post().uri(realmPath + "/users/" + userId + "/role-mappings/realm")
                .header("Authorization", "Bearer " + bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(getRealmRole(roleName)))
                .retrieve().toBodilessEntity();
    }

    public void removeRealmRole(String userId, String roleName) {
        rest.method(HttpMethod.DELETE).uri(realmPath + "/users/" + userId + "/role-mappings/realm")
                .header("Authorization", "Bearer " + bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(getRealmRole(roleName)))
                .retrieve().toBodilessEntity();
    }

    private Map<String, Object> getRealmRole(String roleName) {
        return rest.get().uri(realmPath + "/roles/" + roleName)
                .header("Authorization", "Bearer " + bearer())
                .retrieve().body(new ParameterizedTypeReference<>() {});
    }

    // --- LEAVER: disable, revoke sessions, or delete ---
    public void setEnabled(String userId, boolean enabled) {
        rest.put().uri(realmPath + "/users/" + userId)
                .header("Authorization", "Bearer " + bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("enabled", enabled))
                .retrieve().toBodilessEntity();
    }

    /** Revoke all active sessions immediately (instant deprovision). */
    public void logoutAllSessions(String userId) {
        rest.post().uri(realmPath + "/users/" + userId + "/logout")
                .header("Authorization", "Bearer " + bearer())
                .retrieve().toBodilessEntity();
    }

    public void deleteUser(String userId) {
        rest.method(HttpMethod.DELETE).uri(realmPath + "/users/" + userId)
                .header("Authorization", "Bearer " + bearer())
                .retrieve().toBodilessEntity();
    }
}
