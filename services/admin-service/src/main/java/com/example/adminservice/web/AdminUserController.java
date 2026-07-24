package com.example.adminservice.web;

import com.example.adminservice.keycloak.KeycloakAdminClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Identity lifecycle endpoints (joiner / mover / leaver). The whole controller is
 * locked to app_admin by SecurityConfig; each call delegates to the Keycloak Admin API.
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final KeycloakAdminClient keycloak;

    public AdminUserController(KeycloakAdminClient keycloak) {
        this.keycloak = keycloak;
    }

    public record CreateUserRequest(String username, String email, String firstName,
                                    String lastName, String password) {}

    /** JOINER — provision a new user (optionally with a password). */
    @PostMapping
    public Map<String, Object> create(@RequestBody CreateUserRequest req) {
        String id = keycloak.createUser(req.username(), req.email(), req.firstName(), req.lastName());
        if (req.password() != null && !req.password().isBlank()) {
            keycloak.setPassword(id, req.password());
        }
        return Map.of("id", id, "username", req.username(), "status", "created");
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String search) {
        return keycloak.listUsers(search);
    }

    /** MOVER — grant a realm role. */
    @PostMapping("/{id}/roles/{role}")
    public Map<String, Object> assignRole(@PathVariable String id, @PathVariable String role) {
        keycloak.assignRealmRole(id, role);
        return Map.of("id", id, "role", role, "status", "assigned");
    }

    @DeleteMapping("/{id}/roles/{role}")
    public Map<String, Object> removeRole(@PathVariable String id, @PathVariable String role) {
        keycloak.removeRealmRole(id, role);
        return Map.of("id", id, "role", role, "status", "removed");
    }

    @PutMapping("/{id}/password")
    public Map<String, Object> resetPassword(@PathVariable String id, @RequestBody Map<String, String> body) {
        keycloak.setPassword(id, body.get("password"));
        return Map.of("id", id, "status", "password-reset");
    }

    /** LEAVER (soft) — disable the account; existing sessions still need revoking. */
    @PostMapping("/{id}/disable")
    public Map<String, Object> disable(@PathVariable String id) {
        keycloak.setEnabled(id, false);
        return Map.of("id", id, "status", "disabled");
    }

    @PostMapping("/{id}/enable")
    public Map<String, Object> enable(@PathVariable String id) {
        keycloak.setEnabled(id, true);
        return Map.of("id", id, "status", "enabled");
    }

    /** LEAVER (instant) — revoke all active sessions right now. */
    @PostMapping("/{id}/logout")
    public Map<String, Object> logout(@PathVariable String id) {
        keycloak.logoutAllSessions(id);
        return Map.of("id", id, "status", "sessions-revoked");
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        keycloak.deleteUser(id);
        return Map.of("id", id, "status", "deleted");
    }
}
