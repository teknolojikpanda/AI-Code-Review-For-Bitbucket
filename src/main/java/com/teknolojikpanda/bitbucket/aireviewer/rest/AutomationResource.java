package com.teknolojikpanda.bitbucket.aireviewer.rest;

import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsAlertChannelService;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsAlertChannelService.Channel;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsAlertDeliveryService;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsAlertDeliveryService.Delivery;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsBurstCreditService;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsBurstCreditService.BurstCredit;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsRolloutService;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsRolloutService.CohortMutation;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsRolloutService.CohortRecord;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsRolloutService.ScopeMode;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsRateLimitScope;
import com.teknolojikpanda.bitbucket.aireviewer.service.Page;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewSchedulerStateService;

import javax.annotation.Nullable;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;
import java.util.stream.Collectors;

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
    private final GuardrailsBurstCreditService burstCreditService;
    private final GuardrailsRolloutService rolloutService;

    @Inject
    public AutomationResource(@ComponentImport UserManager userManager,
                              ReviewSchedulerStateService schedulerStateService,
                              GuardrailsAlertChannelService channelService,
                              GuardrailsAlertDeliveryService deliveryService,
                              GuardrailsBurstCreditService burstCreditService,
                              GuardrailsRolloutService rolloutService) {
        this.userManager = Objects.requireNonNull(userManager, "userManager");
        this.schedulerStateService = Objects.requireNonNull(schedulerStateService, "schedulerStateService");
        this.channelService = Objects.requireNonNull(channelService, "channelService");
        this.deliveryService = Objects.requireNonNull(deliveryService, "deliveryService");
        this.burstCreditService = Objects.requireNonNull(burstCreditService, "burstCreditService");
        this.rolloutService = Objects.requireNonNull(rolloutService, "rolloutService");
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
    @Path("/rollout/cohorts")
    public Response listRolloutCohorts(@Context HttpServletRequest request) {
        Access access = requireSystemAdmin(request);
        if (!access.allowed) {
            return access.response;
        }
        Map<String, Object> telemetry = rolloutService.describeTelemetry();
        Map<String, Map<String, Object>> metrics = indexTelemetryByKey(telemetry);
        List<CohortRecord> records = rolloutService.listCohorts();
        List<Map<String, Object>> payload = new ArrayList<>(records.size());
        for (CohortRecord record : records) {
            payload.add(cohortToMap(record, metrics.get(record.getCohortKey())));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("cohorts", payload);
        response.put("defaultMode", telemetry.getOrDefault("defaultMode", "enforced"));
        return Response.ok(response).build();
    }

    @POST
    @Path("/rollout/cohorts")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createRolloutCohort(@Context HttpServletRequest request,
                                        RolloutCohortRequest body) {
        Access access = requireSystemAdmin(request);
        if (!access.allowed) {
            return access.response;
        }
        if (body == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Request body is required"))
                    .build();
        }
        try {
            CohortMutation mutation = body.toMutation(resolveActor(access));
            CohortRecord record = rolloutService.createCohort(mutation);
            return Response.ok(cohortToMap(record, null)).build();
        } catch (IllegalArgumentException ex) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", ex.getMessage()))
                    .build();
        }
    }

    @PUT
    @Path("/rollout/cohorts/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateRolloutCohort(@Context HttpServletRequest request,
                                        @PathParam("id") int id,
                                        RolloutCohortRequest body) {
        Access access = requireSystemAdmin(request);
        if (!access.allowed) {
            return access.response;
        }
        if (body == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Request body is required"))
                    .build();
        }
        try {
            CohortMutation mutation = body.toMutation(resolveActor(access));
            CohortRecord record = rolloutService.updateCohort(id, mutation);
            return Response.ok(cohortToMap(record, null)).build();
        } catch (IllegalArgumentException ex) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", ex.getMessage()))
                    .build();
        }
    }

    @DELETE
    @Path("/rollout/cohorts/{id}")
    public Response deleteRolloutCohort(@Context HttpServletRequest request,
                                        @PathParam("id") int id) {
        Access access = requireSystemAdmin(request);
        if (!access.allowed) {
            return access.response;
        }
        boolean removed = rolloutService.deleteCohort(id);
        if (!removed) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Cohort not found"))
                    .build();
        }
        return Response.noContent().build();
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
        payload.put("aggregates", deliveryService.aggregateRecentDeliveries(200));
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
        GuardrailsAlertDeliveryService.AcknowledgementStats ackStats =
                deliveryService.computeAcknowledgementStats(200);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deliveries", page.getValues());
        payload.put("total", page.getTotal());
        payload.put("limit", page.getLimit());
        payload.put("offset", page.getOffset());
        payload.put("ackStats", ackStats.toMap());
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

    @GET
    @Path("/burst-credits")
    public Response listBurstCredits(@Context HttpServletRequest request,
                                     @QueryParam("includeExpired") boolean includeExpired) {
        Access access = requireSystemAdmin(request);
        if (!access.allowed) {
            return access.response;
        }
        burstCreditService.purgeExpired();
        List<BurstCredit> credits = burstCreditService.listCredits(includeExpired);
        List<Map<String, Object>> items = credits.stream()
                .map(this::burstCreditToMap)
                .collect(Collectors.toList());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", items);
        payload.put("includeExpired", includeExpired);
        return Response.ok(payload).build();
    }

    @POST
    @Path("/burst-credits")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response grantBurstCredit(@Context HttpServletRequest request,
                                     BurstCreditRequest body) {
        Access access = requireSystemAdmin(request);
        if (!access.allowed) {
            return access.response;
        }
        BurstCreditRequest payload = body != null ? body : new BurstCreditRequest();
        GuardrailsRateLimitScope scope = payload.resolveScope();
        String identifier = payload.resolveIdentifier(scope);
        BurstCredit credit = burstCreditService.grantCredit(
                scope,
                identifier,
                payload.tokens != null ? payload.tokens : 5,
                payload.durationMinutes != null ? payload.durationMinutes.longValue() : 60L,
                payload.reason,
                payload.note,
                access.profile.getUserKey().getStringValue(),
                access.profile.getFullName());
        return Response.ok(burstCreditToMap(credit)).build();
    }

    @DELETE
    @Path("/burst-credits/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response revokeBurstCredit(@Context HttpServletRequest request,
                                      @PathParam("id") int id,
                                      BurstCreditRevokeRequest body) {
        Access access = requireSystemAdmin(request);
        if (!access.allowed) {
            return access.response;
        }
        BurstCreditRevokeRequest payload = body != null ? body : new BurstCreditRevokeRequest();
        boolean removed = burstCreditService.revokeCredit(id, payload.note);
        if (!removed) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Burst credit " + id + " not found"))
                    .build();
        }
        return Response.noContent().build();
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

    public static final class RolloutCohortRequest {
        public String key;
        public String displayName;
        public String description;
        public String scopeMode;
        public String projectKey;
        public String repositorySlug;
        public Integer rolloutPercent;
        public String darkFeatureKey;
        public Boolean enabled;

        CohortMutation toMutation(String updatedBy) {
            ScopeMode scope = ScopeMode.fromString(scopeMode);
            int percent = rolloutPercent != null ? rolloutPercent : 100;
            boolean active = enabled == null || enabled;
            return new CohortMutation(
                    key,
                    displayName,
                    description,
                    scope,
                    projectKey,
                    repositorySlug,
                    percent,
                    darkFeatureKey,
                    active,
                    updatedBy);
        }
    }

    public static final class AckRequest {
        public String note;
    }

    public static final class BurstCreditRequest {
        public String scope;
        public String projectKey;
        public String repositorySlug;
        public Integer tokens;
        public Integer durationMinutes;
        public String reason;
        public String note;

        GuardrailsRateLimitScope resolveScope() {
            String value = scope != null ? scope.trim().toLowerCase(Locale.ROOT) : "repository";
            switch (value) {
                case "project":
                    return GuardrailsRateLimitScope.PROJECT;
                case "repo":
                case "repository":
                case "":
                case "null":
                case "default":
                    return GuardrailsRateLimitScope.REPOSITORY;
                default:
                    throw new IllegalArgumentException("Unsupported scope '" + scope + "'. Use 'project' or 'repository'.");
            }
        }

        String resolveIdentifier(GuardrailsRateLimitScope target) {
            if (target == GuardrailsRateLimitScope.PROJECT) {
                if (projectKey == null || projectKey.trim().isEmpty()) {
                    throw new IllegalArgumentException("projectKey is required for project burst credits");
                }
                return projectKey.trim();
            }
            if (repositorySlug == null || repositorySlug.trim().isEmpty()) {
                throw new IllegalArgumentException("repositorySlug is required for repository burst credits");
            }
            return repositorySlug.trim();
        }
    }

    public static final class BurstCreditRevokeRequest {
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

    private Map<String, Object> burstCreditToMap(BurstCredit credit) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", credit.getId());
        map.put("scope", credit.getScope().name().toLowerCase(Locale.ROOT));
        map.put("identifier", credit.getIdentifier());
        map.put("tokensGranted", credit.getTokensGranted());
        map.put("tokensConsumed", credit.getTokensConsumed());
        map.put("tokensRemaining", credit.getTokensRemaining());
        map.put("createdAt", credit.getCreatedAt());
        map.put("expiresAt", credit.getExpiresAt());
        map.put("active", credit.isActive());
        map.put("createdBy", credit.getCreatedBy());
        map.put("createdByDisplayName", credit.getCreatedByDisplayName());
        map.put("reason", credit.getReason());
        map.put("note", credit.getNote());
        map.put("lastConsumedAt", credit.getLastConsumedAt());
        return map;
    }

    private Map<String, Object> cohortToMap(CohortRecord record,
                                            @Nullable Map<String, Object> metrics) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", record.getId());
        map.put("key", record.getCohortKey());
        map.put("displayName", record.getDisplayName());
        map.put("description", record.getDescription());
        map.put("scopeMode", record.getScopeMode().name().toLowerCase(Locale.ROOT));
        map.put("projectKey", record.getProjectKey());
        map.put("repositorySlug", record.getRepositorySlug());
        map.put("rolloutPercent", record.getRolloutPercent());
        map.put("darkFeatureKey", record.getDarkFeatureKey());
        map.put("enabled", record.isEnabled());
        map.put("createdAt", record.getCreatedAt());
        map.put("updatedAt", record.getUpdatedAt());
        map.put("updatedBy", record.getUpdatedBy());
        map.put("metrics", metrics != null ? metrics : Map.of());
        return map;
    }

    private Map<String, Map<String, Object>> indexTelemetryByKey(@Nullable Map<String, Object> telemetry) {
        Map<String, Map<String, Object>> index = new LinkedHashMap<>();
        if (telemetry == null) {
            return index;
        }
        Object cohortsObj = telemetry.get("cohorts");
        if (!(cohortsObj instanceof List<?>)) {
            return index;
        }
        List<?> list = (List<?>) cohortsObj;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }
            Map<?, ?> raw = (Map<?, ?>) item;
            Object key = raw.get("key");
            if (!(key instanceof String)) {
                continue;
            }
            Map<String, Object> metrics = toStringObjectMap(raw.get("metrics"));
            index.put((String) key, metrics);
        }
        return index;
    }

    private Map<String, Object> toStringObjectMap(@Nullable Object value) {
        if (!(value instanceof Map<?, ?>)) {
            return Map.of();
        }
        Map<?, ?> raw = (Map<?, ?>) value;
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() instanceof String) {
                map.put((String) entry.getKey(), entry.getValue());
            }
        }
        return map;
    }

    private String resolveActor(Access access) {
        if (access != null && access.profile != null) {
            String username = access.profile.getUsername();
            if (username != null && !username.isBlank()) {
                return username;
            }
            if (access.profile.getUserKey() != null) {
                String key = access.profile.getUserKey().getStringValue();
                if (key != null && !key.isBlank()) {
                    return key;
                }
            }
            String displayName = access.profile.getFullName();
            if (displayName != null && !displayName.isBlank()) {
                return displayName;
            }
        }
        return "system";
    }
}
