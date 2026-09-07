package io.ib67.prts.auth;

import io.quarkus.security.identity.request.BaseAuthenticationRequest;
import lombok.Getter;

import java.util.Objects;

/**
 * Authentication request carrying a personal access token.
 */
@Getter
public class AccessTokenAuthenticationRequest extends BaseAuthenticationRequest {

    private final String token;

    public AccessTokenAuthenticationRequest(String token) {
        this.token = Objects.requireNonNull(token, "token");
    }

}
