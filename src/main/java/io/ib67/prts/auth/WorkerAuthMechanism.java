package io.ib67.prts.auth;

import io.agroal.api.security.NamePrincipal;
import io.ib67.prts.agent.worker.WorkerConfig;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.annotation.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
@HttpAuthenticationMechanism("worker-api")
public class WorkerAuthMechanism
        implements io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism {
    @Inject
    WorkerConfig workerConfig;

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {

        String authorization =
                context.request().getHeader("Authorization");

        if (authorization == null ||
                !authorization.startsWith("Bearer ")) {
            return Uni.createFrom().nullItem();
        }

        var token = authorization.substring("Bearer ".length());
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
}
