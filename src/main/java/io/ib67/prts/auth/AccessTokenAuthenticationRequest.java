package io.ib67.prts.auth;

import io.quarkus.security.identity.request.BaseAuthenticationRequest;
import lombok.Getter;

import java.util.Objects;

/**
 * A presented personal access token. Its own request type rather than quarkus-oidc's
 * {@code TokenAuthenticationRequest}: {@code IdentityProviderManager} dispatches on the exact class,
 * so sharing one would mean two providers claiming the same request.
 */
@Getter
public class AccessTokenAuthenticationRequest extends BaseAuthenticationRequest {

    private final String token;

    public AccessTokenAuthenticationRequest(String token) {
        this.token = Objects.requireNonNull(token, "token");
    }

}
