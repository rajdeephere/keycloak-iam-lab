package com.example.webclient.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Controller
public class HomeController {

    private final RestClient rest = RestClient.create();

    @Value("${product-service.url}")
    private String productServiceUrl;

    /** Public landing page. */
    @GetMapping("/")
    public String index() {
        return "index";
    }

    /** Shows who logged in — read straight from the OIDC ID token. */
    @GetMapping("/me")
    public String me(@AuthenticationPrincipal OidcUser user, Model model) {
        model.addAttribute("username", user.getPreferredUsername());
        model.addAttribute("email", user.getEmail());
        model.addAttribute("claims", user.getClaims());
        return "me";
    }

    /**
     * Calls the protected product-service using the ACCESS TOKEN obtained for
     * this logged-in user. Spring injects the authorized client (with its token)
     * for the "keycloak" registration.
     */
    @GetMapping("/products")
    public String products(@RegisteredOAuth2AuthorizedClient("keycloak") OAuth2AuthorizedClient client,
                           Model model) {
        String accessToken = client.getAccessToken().getTokenValue();
        try {
            List<?> products = rest.get()
                    .uri(productServiceUrl + "/api/products")
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(List.class);
            model.addAttribute("products", products);
        } catch (Exception e) {
            model.addAttribute("error", e.getMessage());
        }
        // Show a short preview of the token so the demo makes the flow tangible.
        model.addAttribute("tokenPreview", accessToken.substring(0, Math.min(40, accessToken.length())) + "...");
        return "products";
    }
}
