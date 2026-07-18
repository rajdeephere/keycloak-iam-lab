package com.example.productservice.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final List<Product> products = new CopyOnWriteArrayList<>(List.of(
            new Product(1L, "Keyboard", "peripherals", 49.99),
            new Product(2L, "Monitor", "displays", 199.00),
            new Product(3L, "Standing Desk", "furniture", 349.50)
    ));
    private final AtomicLong seq = new AtomicLong(3);

    /**
     * Any authenticated user (app_user or app_admin) can read.
     * Requires ROLE_app_user OR ROLE_app_admin.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('app_user', 'app_admin')")
    public List<Product> list() {
        return products;
    }

    /** Only admins can create. Requires ROLE_app_admin. */
    @PostMapping
    @PreAuthorize("hasRole('app_admin')")
    public Product create(@RequestBody Product incoming) {
        Product created = new Product(
                seq.incrementAndGet(), incoming.name(), incoming.category(), incoming.price());
        products.add(created);
        return created;
    }

    /**
     * Echoes back who the caller is, straight from the validated JWT.
     * Handy for demoing what the token carries.
     */
    @GetMapping("/whoami")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> whoami(@AuthenticationPrincipal Jwt jwt) {
        return Map.of(
                "subject", jwt.getSubject(),
                "username", jwt.getClaimAsString("preferred_username"),
                "audience", jwt.getAudience(),
                "issuer", jwt.getIssuer().toString(),
                "authorities_source", "realm_access.roles"
        );
    }
}
