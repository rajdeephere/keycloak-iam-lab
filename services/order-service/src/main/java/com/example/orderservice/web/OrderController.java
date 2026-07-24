package com.example.orderservice.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final RestClient rest = RestClient.create();
    private final OAuth2AuthorizedClientManager authorizedClientManager;

    @Value("${product-service.url}")
    private String productServiceUrl;

    public OrderController(OAuth2AuthorizedClientManager authorizedClientManager) {
        this.authorizedClientManager = authorizedClientManager;
    }

    /**
     * PATTERN 1 — TOKEN RELAY (on behalf of the user).
     * We forward the CALLER'S access token when calling product-service, so the
     * downstream call carries the user's identity and roles. product-service sees
     * "alice", not "order-service".
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('app_user','app_admin')")
    public Map<String, Object> orders(@AuthenticationPrincipal Jwt jwt) {
        List<?> products = fetchProducts(jwt.getTokenValue());
        return Map.of(
            "mode", "TOKEN RELAY - product-service called as user '" + jwt.getClaimAsString("preferred_username") + "'",
            "orders", List.of(
                Map.of("id", 1, "item", "Keyboard x1"),
                Map.of("id", 2, "item", "Monitor x2")),
            "productsSeenByOrderService", products
        );
    }

    /**
     * PATTERN 2 — CLIENT CREDENTIALS (as the service itself).
     * No user context. order-service authenticates as ITSELF (its service account)
     * and calls product-service. Use this for background jobs / M2M where there is
     * no user on whose behalf to act.
     */
    @GetMapping("/sync")
    @PreAuthorize("hasAnyRole('app_user','app_admin')")
    public Map<String, Object> sync() {
        OAuth2AuthorizedClient client = authorizedClientManager.authorize(
                OAuth2AuthorizeRequest.withClientRegistrationId("order-service")
                        .principal("order-service")
                        .build());
        String machineToken = client.getAccessToken().getTokenValue();
        List<?> products = fetchProducts(machineToken);
        return Map.of(
            "mode", "CLIENT CREDENTIALS - product-service called as service-account 'order-service'",
            "productsSeenByOrderService", products
        );
    }

    private List<?> fetchProducts(String bearerToken) {
        return rest.get()
                .uri(productServiceUrl + "/api/products")
                .header("Authorization", "Bearer " + bearerToken)
                .retrieve()
                .body(List.class);
    }
}
