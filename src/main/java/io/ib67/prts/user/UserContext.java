package io.ib67.prts.user;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class UserContext {
    @Inject SecurityIdentity identity;

    public User get(){
        return identity.getAttribute(User.class.getName());
    }
}
