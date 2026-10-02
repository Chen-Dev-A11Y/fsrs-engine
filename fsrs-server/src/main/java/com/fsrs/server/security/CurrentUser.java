package com.fsrs.server.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Access to the authenticated caller.
 *
 * <p>Controllers take a bare {@code userId} instead of a principal object so that the
 * tenancy rule ("you only ever see your own rows") is stated in the method signature and
 * cannot be forgotten: every query is already scoped by the id that arrives here.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** @throws IllegalStateException when called on an unauthenticated request, i.e. a wiring bug */
    public static long id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof JwtService.AuthUser user)) {
            throw new IllegalStateException("no authenticated user in the security context");
        }
        return user.userId();
    }
}
