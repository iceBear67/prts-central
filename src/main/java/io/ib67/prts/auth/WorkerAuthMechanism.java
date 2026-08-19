package io.ib67.prts.auth;

import io.agroal.api.security.NamePrincipal;
import io.ib67.prts.agent.worker.WorkerConfig;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpCredentialTransport;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

@ApplicationScoped
public class WorkerAuthMechanism implements HttpAuthenticationMechanism {
    @Inject
    WorkerConfig workerConfig;

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        String token =
                context.request().getHeader("X-Worker-Token");
        if (token == null ){
            return Uni.createFrom().nullItem();
        }
        var sk = workerConfig.secret();
        if(token.length() != sk.length() || !sk.equals(token)) {
            return Uni.createFrom().nullItem();
        }
        SecurityIdentity identity =
                QuarkusSecurityIdentity.builder()
                        // TODO should we care about each worker's identity?
                        .setPrincipal(new NamePrincipal("some-worker"))
                        .addRole("worker")
                        .build();

        return Uni.createFrom().item(identity);
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        return Uni.createFrom().item(
                new ChallengeData(401, null, null)
        );
    }

    @Override
    public Uni<HttpCredentialTransport> getCredentialTransport(RoutingContext context) {
        return Uni.createFrom().item(new HttpCredentialTransport(
                HttpCredentialTransport.Type.OTHER_HEADER,
                "X-Worker-Token",
                "worker-token"
        ));
    }
}
