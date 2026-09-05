package io.ib67.prts.user;

import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class UserContext {
    @Inject SecurityIdentity identity;

    public User get(){
        return identity.getAttribute(User.class.getName());
    }

    /**
     * For endpoints that act on the caller themselves and so carry no {@code @RequirePermission} to
     * refuse an identity with no local user.
     */
    public User require() {
        var user = get();
        if (user == null) {
            throw new UnauthorizedException();
        }
        return user;
    }
}
