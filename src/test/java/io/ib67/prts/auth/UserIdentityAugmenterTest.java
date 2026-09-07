package io.ib67.prts.auth;

import io.ib67.prts.user.PermissionService;
import io.ib67.prts.user.User;
import io.ib67.prts.user.UserService;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserIdentityAugmenterTest {

    private static final String ISSUER = "https://issuer.example";
    private static final String SUBJECT = "subject-1";
    private static final String EMAIL = "alice@example.com";
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private final UserService userService = mock(UserService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final AuthenticationRequestContext context = mock(AuthenticationRequestContext.class);

    private final UserIdentityAugmenter augmenter = new UserIdentityAugmenter();

    private final User user = User.builder().id(USER_ID).name("Alice").email(EMAIL).build();

    @BeforeEach
    void setUp() {
        augmenter.userService = userService;
        augmenter.permissionService = permissionService;
        // runBlocking is the only hop off the event loop; run the body on the spot.
        when(context.runBlocking(any())).thenAnswer(invocation ->
                Uni.createFrom().item(invocation.getArgument(0, Supplier.class).get()));
    }

    private SecurityIdentity augment(SecurityIdentity identity) {
        return augmenter.augment(identity, context).await().indefinitely();
    }

    private static JsonWebToken token() {
        var token = mock(JsonWebToken.class);
        when(token.getIssuer()).thenReturn(ISSUER);
        when(token.getSubject()).thenReturn(SUBJECT);
        return token;
    }

    private static SecurityIdentity identityOf(JsonWebToken token) {
        return QuarkusSecurityIdentity.builder().setPrincipal(token).build();
    }

    private JsonWebToken firstLoginToken() {
        when(userService.findByIssuerAndSubject(ISSUER, SUBJECT)).thenReturn(Optional.empty());
        var token = token();
        when(token.<Object>getClaim("email")).thenReturn(EMAIL);
        return token;
    }

    @Test
    void anAnonymousIdentityIsUntouched() {
        var identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("anonymous"))
                .setAnonymous(true)
                .build();

        assertSame(identity, augment(identity));
        verify(context, never()).runBlocking(any());
    }

    /** What @TestSecurity produces: a bare principal with no issuer, hence no local user. */
    @Test
    void anIdentityWithoutAnIssuerIsUntouched() {
        var identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("alice"))
                .build();

        assertSame(identity, augment(identity));
        verify(context, never()).runBlocking(any());
    }

    @Test
    void aKnownUserIsAttachedWithTheUserRole() {
        when(userService.findByIssuerAndSubject(ISSUER, SUBJECT)).thenReturn(Optional.of(user));
        when(permissionService.isAdmin(USER_ID)).thenReturn(false);

        var augmented = augment(identityOf(token()));

        assertSame(user, augmented.getAttribute(User.class.getName()));
        assertTrue(augmented.hasRole("user"));
        assertFalse(augmented.hasRole("admin"));
        verify(userService, never()).provision(any(), any(), any(), any());
    }

    @Test
    void anAdminGetsTheAdminRoleInstead() {
        when(userService.findByIssuerAndSubject(ISSUER, SUBJECT)).thenReturn(Optional.of(user));
        when(permissionService.isAdmin(USER_ID)).thenReturn(true);

        var augmented = augment(identityOf(token()));

        assertTrue(augmented.hasRole("admin"));
        assertFalse(augmented.hasRole("user"));
    }

    @Test
    void whatTheIdentityAlreadyCarriedSurvives() {
        when(userService.findByIssuerAndSubject(ISSUER, SUBJECT)).thenReturn(Optional.of(user));
        var identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(token())
                .addRole("tenant")
                .addAttribute("tier", "gold")
                .build();

        var augmented = augment(identity);

        assertTrue(augmented.hasRole("tenant"));
        assertEquals("gold", augmented.getAttribute("tier"));
    }

    @Test
    void aFirstLoginProvisionsTheUser() {
        var token = firstLoginToken();
        when(token.<Object>getClaim("name")).thenReturn("Alice");
        when(userService.provision(any(), any(), any(), any())).thenReturn(user);

        var augmented = augment(identityOf(token));

        verify(userService).provision(ISSUER, SUBJECT, "Alice", EMAIL);
        assertSame(user, augmented.getAttribute(User.class.getName()));
    }

    @Test
    void theDisplayNameFallsBackToPreferredUsername() {
        var token = firstLoginToken();
        when(token.<Object>getClaim("preferred_username")).thenReturn("alice42");
        when(userService.provision(any(), any(), any(), any())).thenReturn(user);

        augment(identityOf(token));

        verify(userService).provision(ISSUER, SUBJECT, "alice42", EMAIL);
    }

    @Test
    void aBlankNameClaimCountsAsAbsent() {
        var token = firstLoginToken();
        when(token.<Object>getClaim("name")).thenReturn("   ");
        when(token.<Object>getClaim("preferred_username")).thenReturn("alice42");
        when(userService.provision(any(), any(), any(), any())).thenReturn(user);

        augment(identityOf(token));

        verify(userService).provision(ISSUER, SUBJECT, "alice42", EMAIL);
    }

    @Test
    void theDisplayNameFallsBackToTheSubject() {
        var token = firstLoginToken();
        when(userService.provision(any(), any(), any(), any())).thenReturn(user);

        augment(identityOf(token));

        verify(userService).provision(ISSUER, SUBJECT, SUBJECT, EMAIL);
    }

    /** No email means no account: the User entity requires one. */
    @Test
    void aTokenWithoutAnEmailIsNotRegistered() {
        when(userService.findByIssuerAndSubject(ISSUER, SUBJECT)).thenReturn(Optional.empty());
        var identity = identityOf(token());

        assertSame(identity, augment(identity));
        verify(userService, never()).provision(any(), any(), any(), any());
    }

    /** Two first logins at once: one insert loses, and must still see the row the winner wrote. */
    @Test
    void aConcurrentFirstLoginFallsBackToTheRowTheOtherThreadWrote() {
        var token = token();
        when(token.<Object>getClaim("email")).thenReturn(EMAIL);
        when(userService.findByIssuerAndSubject(ISSUER, SUBJECT))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(user));
        when(userService.provision(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("duplicate key"));

        var augmented = augment(identityOf(token));

        assertSame(user, augmented.getAttribute(User.class.getName()));
    }

    @Test
    void aProvisioningFailureThatIsNoRaceLeavesTheIdentityAlone() {
        var token = firstLoginToken();
        when(userService.provision(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("database is down"));
        var identity = identityOf(token);

        assertSame(identity, augment(identity));
    }

    /** Not every provider yields a JsonWebToken principal; the issuer may arrive as an attribute. */
    @Test
    void theIssuerMayComeFromAnAttribute() {
        when(userService.findByIssuerAndSubject(ISSUER, SUBJECT)).thenReturn(Optional.of(user));
        var identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(SUBJECT))
                .addAttribute("issuer", ISSUER)
                .build();

        assertSame(user, augment(identity).getAttribute(User.class.getName()));
    }
}
