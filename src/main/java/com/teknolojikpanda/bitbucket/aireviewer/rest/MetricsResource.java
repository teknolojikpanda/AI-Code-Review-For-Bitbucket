package com.teknolojikpanda.bitbucket.aireviewer.rest;

import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsTelemetryService;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Map;
import java.util.Objects;

import static javax.ws.rs.core.Response.Status.FORBIDDEN;
import static javax.ws.rs.core.Response.Status.UNAUTHORIZED;

@Path("/metrics")
@Produces(MediaType.APPLICATION_JSON)
@Named
public class MetricsResource {

    private final UserManager userManager;
    private final GuardrailsTelemetryService telemetryService;

    @Inject
    public MetricsResource(@ComponentImport UserManager userManager,
                           GuardrailsTelemetryService telemetryService) {
        this.userManager = Objects.requireNonNull(userManager, "userManager");
        this.telemetryService = Objects.requireNonNull(telemetryService, "telemetryService");
    }

    @GET
    public Response exportMetrics(@Context HttpServletRequest request) {
        Access access = requireSystemAdmin(request);
        if (!access.allowed) {
            return access.response;
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>(telemetryService.exportMetrics());
        payload.put("requestedBy", access.profile.getUserKey().getStringValue());
        return Response.ok(payload).build();
    }

    private Access requireSystemAdmin(HttpServletRequest request) {
        UserProfile profile = userManager.getRemoteUser(request);
        if (profile == null) {
            return Access.denied(Response.status(UNAUTHORIZED)
                    .entity(Map.of("error", "Authentication required")).build());
        }
        if (!userManager.isSystemAdmin(profile.getUserKey())) {
            return Access.denied(Response.status(FORBIDDEN)
                    .entity(Map.of("error", "Administrator privileges required")).build());
        }
        return Access.allowed(profile);
    }

    private static final class Access {
        final boolean allowed;
        final Response response;
        final UserProfile profile;

        private Access(boolean allowed, Response response, UserProfile profile) {
            this.allowed = allowed;
            this.response = response;
            this.profile = profile;
        }

        static Access allowed(UserProfile profile) {
            return new Access(true, null, profile);
        }

        static Access denied(Response response) {
            return new Access(false, response, null);
        }
    }
}
