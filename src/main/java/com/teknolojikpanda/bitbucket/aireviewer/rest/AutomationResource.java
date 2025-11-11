package com.teknolojikpanda.bitbucket.aireviewer.rest;

import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsAlertChannelService;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsAlertChannelService.Channel;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsAlertDeliveryService;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsAlertDeliveryService.Delivery;
import com.teknolojikpanda.bitbucket.aireviewer.service.Page;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewSchedulerStateService;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static javax.ws.rs.core.Response.Status.FORBIDDEN;
import static javax.ws.rs.core.Response.Status.UNAUTHORIZED;

@Path("/automation")
@Produces(MediaType.APPLICATION_JSON)
@Named
public class AutomationResource {

    private final UserManager userManager;
    private final ReviewSchedulerStateService schedulerStateService;
    private final GuardrailsAlertChannelService channelService;
    private final GuardrailsAlertDeliveryService deliveryService;

    @Inject
    public AutomationResource(@ComponentImport UserManager userManager,
                              ReviewSchedulerStateService schedulerStateService,
                              GuardrailsAlertChannelService channelService,
                              GuardrailsAlertDeliveryService deliveryService) {
        this.userManager = Objects.requireNonNull(userManager, "userManager");
        this.schedulerStateService = Objects.requireNonNull(schedulerStateService, "schedulerStateService");
        this.channelService = Objects.requireNonNull(channelService, "channelService");
        this.deliveryService = Objects.requireNonNull(deliveryService, "deliveryService");
    }

    @POST
    @Path("/rollout/{mode}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response changeSchedulerState(@Context HttpServletRequest request,
                                         @PathParam("mode") String modePath,
                                         Map<String, Object> body) {
        Access access = requireSystemAdmin(request);
        if (!access.allowed) {
            return access.response;
        }
        String mode = modePath != null ? modePath.trim().toUpperCase() : "ACTIVE";
        ReviewSchedulerStateService.SchedulerState.Mode target;
        switch (mode) {
            case "PAUSE":
            case "PAUSED":
                target = ReviewSchedulerStateService.SchedulerState.Mode.PAUSED;
                break;
            case "DRAIN":
            case "DRAINING":
                target = ReviewSchedulerStateService.SchedulerState.Mode.DRAINING;
                break;
            default:
                target = ReviewSchedulerStateService.SchedulerState.Mode.ACTIVE;
        }
        String reason = body != null ? Objects.toString(body.get("reason"), null) : null;
        ReviewSchedulerStateService.SchedulerState state = schedulerStateService.updateState(
                target,
                access.profile.getUserKey().getStringValue(),
                access.profile.getFullName(),
                reason);
        Map<String, Object> payload = new HashMap<>();
        payload.put("mode", state.getMode().name());
        payload.put("reason", state.getReason());
        payload.put("updatedBy", state.getUpdatedBy());
        payload.put("updatedAt", state.getUpdatedAt());
        return Response.ok(payload).build();
    }

    @GET
    @Path("/rollout/state")
    public Response getRolloutState(@Context HttpServletRequest request) {
        Access access = requireSystemAdmin(request);
        if (!access.allowed) {
            return access.response;
        }
        ReviewSchedulerStateService.SchedulerState state = schedulerStateService.getState();
        return Response.ok(schedulerStateToMap(state)).build();
    }

    @GET
    @Path("/channels")
    public Response listChannels(@Context HttpServletRequest request,
                                 @QueryParam("limit") Integer limitParam,
                                 @QueryParam("offset") Integer offsetParam) {
        Access access = requireSystemAdmin(request);
        if (!access.allowed) {
            return access.response;
        }
        int limit = limitParam == null ? 50 : Math.max(1, Math.min(limitParam, 200));
        int offset = offsetParam == null ? 0 : Math.max(0, offsetParam);
        Page<Channel> page = channelService.listChannels(offset, limit);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channels", page.getValues());
        payload.put("total", page.getTotal());
        payload.put("limit", page.getLimit());
        payload.put("offset", page.getOffset());
        return Response.ok(payload).build();
    }

    @POST
    @Path("/channels")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createChannel(@Context HttpServletRequest request,
                                  ChannelRequest body) {
        Access access = requireSystemAdmin(request);
        if (!access.allowed) {
            return access.response;
        }
        body.validateForCreate();
        Channel channel = channelService.createChannel(
                body.url,
                body.description,
                body.enabled != null ? body.enabled : true,
                body.signRequests != null ? body.signRequests : true,
                body.secret,
                body.maxRetries,
                body.retryBackoffSeconds);
        return Response.ok(channel).build();
    }

    @PUT
    @Path("/channels/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateChannel(@Context HttpServletRequest request,
                                  @PathParam("id") int id,
                                  ChannelRequest body) {
        Access access = requireSystemAdmin(request);
        if (!access.allowed) {
            return access.response;
        }
        Channel channel = channelService.updateChannel(
                id,
                body.description,
                body.enabled,
                body.signRequests,
                body.rotateSecret,
                body.secret,
                body.maxRetries,
                body.retryBackoffSeconds);
        return Response.ok(channel).build();
    }

    @DELETE
    @Path("/channels/{id}")
    public Response deleteChannel(@Context HttpServletRequest request,
                                  @PathParam("id") int id) {
        Access access = requireSystemAdmin(request);
        if (!access.allowed) {
            return access.response;
        }
        channelService.deleteChannel(id);
        return Response.noContent().build();
    }

    @POST
    @Path("/channels/{id}/test")
    public Response testChannel(@Context HttpServletRequest request,
                                @PathParam("id") int id) {
        Access access = requireSystemAdmin(request);
        if (!access.allowed) {
            return access.response;
        }
        boolean delivered = channelService.sendTestAlert(id);
        return Response.ok(Map.of(
                "channelId", id,
                "delivered", delivered)).build();
    }

    @GET
    @Path("/alerts/deliveries")
    public Response listDeliveries(@Context HttpServletRequest request,
                                   @QueryParam("limit") Integer limitParam,
                                   @QueryParam("offset") Integer offsetParam) {
        Access access = requireSystemAdmin(request);
        if (!access.allowed) {
            return access.response;
        }
        int limit = limitParam == null ? 50 : Math.max(1, Math.min(limitParam, 200));
        int offset = offsetParam == null ? 0 : Math.max(0, offsetParam);
        Page<Delivery> page = deliveryService.listDeliveries(offset, limit);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deliveries", page.getValues());
        payload.put("total", page.getTotal());
        payload.put("limit", page.getLimit());
        payload.put("offset", page.getOffset());
        return Response.ok(payload).build();
    }

    @POST
    @Path("/alerts/deliveries/{id}/ack")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response acknowledgeDelivery(@Context HttpServletRequest request,
                                        @PathParam("id") int id,
                                        AckRequest body) {
        Access access = requireSystemAdmin(request);
        if (!access.allowed) {
            return access.response;
        }
        AckRequest payload = body != null ? body : new AckRequest();
        Delivery delivery = deliveryService.acknowledge(
                id,
                access.profile.getUserKey().getStringValue(),
                access.profile.getFullName(),
                payload.note);
        return Response.ok(delivery).build();
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

    public static final class ChannelRequest {
        public String url;
        public String description;
        public Boolean enabled;
        public Boolean signRequests;
        public String secret;
        public Integer maxRetries;
        public Integer retryBackoffSeconds;
        public Boolean rotateSecret;

        void validateForCreate() {
            if (url == null || url.trim().isEmpty()) {
                throw new IllegalArgumentException("Channel URL is required");
            }
        }
    }

    public static final class AckRequest {
        public String note;
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

    private Map<String, Object> schedulerStateToMap(ReviewSchedulerStateService.SchedulerState state) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mode", state.getMode().name());
        map.put("updatedBy", state.getUpdatedBy());
        map.put("updatedByDisplayName", state.getUpdatedByDisplayName());
        map.put("reason", state.getReason());
        map.put("updatedAt", state.getUpdatedAt());
        return map;
    }
}
