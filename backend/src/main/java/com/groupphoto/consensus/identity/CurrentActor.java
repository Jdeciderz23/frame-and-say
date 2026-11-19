package com.groupphoto.consensus.identity;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class CurrentActor {
    public String requireId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof BearerTokenAuthentication bearer && bearer.isAuthenticated()) {
            return bearer.getName();
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "A valid bearer token is required");
    }
}
