package com.teknolojikpanda.bitbucket.aireviewer.rest;

import com.atlassian.sal.api.user.UserKey;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsAlertChannelService;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsAlertChannelService.Channel;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsAlertDeliveryService;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsAlertDeliveryService.Delivery;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsBurstCreditService;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsBurstCreditService.BurstCredit;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsRateLimitScope;
import com.teknolojikpanda.bitbucket.aireviewer.service.Page;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewSchedulerStateService;
import org.junit.Before;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class AutomationResourceTest {

    private UserManager userManager;
    private ReviewSchedulerStateService schedulerStateService;
    private GuardrailsAlertChannelService channelService;
    private GuardrailsAlertDeliveryService deliveryService;
    private GuardrailsBurstCreditService burstCreditService;
    private AutomationResource resource;
    private HttpServletRequest request;
    private UserProfile profile;

    @Before
    public void setUp() {
        userManager = mock(UserManager.class);
        schedulerStateService = mock(ReviewSchedulerStateService.class);
        channelService = mock(GuardrailsAlertChannelService.class);
        deliveryService = mock(GuardrailsAlertDeliveryService.class);
        burstCreditService = mock(GuardrailsBurstCreditService.class);
        resource = new AutomationResource(userManager, schedulerStateService, channelService, deliveryService, burstCreditService);
        request = mock(HttpServletRequest.class);
        profile = mock(UserProfile.class);
        Channel channel = new Channel(1, "https://example", "Ops Pager", true, 0L, 0L, true, "secret", 2, 5);
        Page<Channel> page = new Page<>(List.of(channel), 1, 50, 0);
        when(channelService.listChannels(anyInt(), anyInt())).thenReturn(page);
        when(channelService.sendTestAlert(anyInt())).thenReturn(true);
        Delivery delivery = new Delivery(42, 1, "https://example", "Ops Pager", System.currentTimeMillis(),
                true, false, 200, "{}", null, false, null, null, 0L, null);
        Page<Delivery> deliveryPage = new Page<>(List.of(delivery), 1, 50, 0);
        when(deliveryService.listDeliveries(anyInt(), anyInt())).thenReturn(deliveryPage);
        GuardrailsAlertDeliveryService.AcknowledgementStats ackStats =
                new GuardrailsAlertDeliveryService.AcknowledgementStats(2, 60_000L, 30_000d, 1);
        when(deliveryService.computeAcknowledgementStats(anyInt())).thenReturn(ackStats);
        when(deliveryService.acknowledge(anyInt(), any(), any(), any())).thenReturn(delivery);
    }

    @Test
    public void rolloutChangeRequiresAdmin() {
        when(userManager.getRemoteUser(request)).thenReturn(null);

        Response response = resource.changeSchedulerState(request, "pause", Map.of());

        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
        verifyNoInteractions(schedulerStateService);
    }

    @Test
    public void rolloutChangeUpdatesState() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        UserKey key = new UserKey("admin");
        when(profile.getUserKey()).thenReturn(key);
        when(profile.getFullName()).thenReturn("Admin");
        when(userManager.isSystemAdmin(key)).thenReturn(true);
        ReviewSchedulerStateService.SchedulerState state =
                new ReviewSchedulerStateService.SchedulerState(
                        ReviewSchedulerStateService.SchedulerState.Mode.PAUSED,
                        "admin", "Admin", "maintenance", System.currentTimeMillis());
        when(schedulerStateService.updateState(any(), any(), any(), any())).thenReturn(state);

        Response response = resource.changeSchedulerState(request, "pause", Map.of("reason", "maintenance"));

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        verify(schedulerStateService).updateState(
                ReviewSchedulerStateService.SchedulerState.Mode.PAUSED,
                "admin",
                "Admin",
                "maintenance");
    }

    @Test
    public void channelCrudRequiresAdmin() {
        when(userManager.getRemoteUser(request)).thenReturn(null);

        Response response = resource.listChannels(request, null, null);

        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
        verifyNoInteractions(channelService);
    }

    @Test
    public void channelCrudOperations() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        UserKey key = new UserKey("admin");
        when(profile.getUserKey()).thenReturn(key);
        when(userManager.isSystemAdmin(key)).thenReturn(true);
        Channel channel = new Channel(1, "https://example", "Ops Pager", true, 0L, 0L, true, "secret", 2, 5);
        when(channelService.listChannels(anyInt(), anyInt())).thenReturn(new Page<>(List.of(channel), 1, 50, 0));
        when(channelService.createChannel(anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any(), any())).thenReturn(channel);
        when(channelService.updateChannel(eq(1), any(), any(), any(), any(), any(), any(), any())).thenReturn(channel);

        Response list = resource.listChannels(request, null, null);
        assertEquals(Response.Status.OK.getStatusCode(), list.getStatus());
        verify(channelService).listChannels(0, 50);

        AutomationResource.ChannelRequest body = new AutomationResource.ChannelRequest();
        body.url = "https://example";
        body.description = "Ops Pager";
        body.enabled = true;

        Response create = resource.createChannel(request, body);
        assertEquals(Response.Status.OK.getStatusCode(), create.getStatus());
        verify(channelService).createChannel(eq("https://example"), eq("Ops Pager"), eq(true), eq(true), isNull(), isNull(), isNull());

        Response update = resource.updateChannel(request, 1, body);
        assertEquals(Response.Status.OK.getStatusCode(), update.getStatus());

        Response delete = resource.deleteChannel(request, 1);
        assertEquals(Response.Status.NO_CONTENT.getStatusCode(), delete.getStatus());
        verify(channelService).deleteChannel(1);
    }

    @Test
    public void rolloutStateEndpointReturnsStateForAdmins() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        UserKey key = new UserKey("admin");
        when(profile.getUserKey()).thenReturn(key);
        when(userManager.isSystemAdmin(key)).thenReturn(true);
        ReviewSchedulerStateService.SchedulerState state =
                new ReviewSchedulerStateService.SchedulerState(
                        ReviewSchedulerStateService.SchedulerState.Mode.ACTIVE,
                        "admin",
                        "Admin",
                        "all good",
                        123L);
        when(schedulerStateService.getState()).thenReturn(state);

        Response response = resource.getRolloutState(request);

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getEntity();
        assertEquals("ACTIVE", payload.get("mode"));
        assertEquals("all good", payload.get("reason"));
    }

    @Test
    public void testChannelEndpointTriggersDelivery() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        UserKey key = new UserKey("admin");
        when(profile.getUserKey()).thenReturn(key);
        when(userManager.isSystemAdmin(key)).thenReturn(true);
        when(channelService.sendTestAlert(1)).thenReturn(true);

        Response response = resource.testChannel(request, 1);

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        verify(channelService).sendTestAlert(1);
    }

    @Test
    public void deliveriesRequireAdmin() {
        when(userManager.getRemoteUser(request)).thenReturn(null);

        Response response = resource.listDeliveries(request, null, null);

        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
        verifyNoInteractions(deliveryService);
    }

    @Test
    public void deliveriesEndpointReturnsPage() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        UserKey key = new UserKey("admin");
        when(profile.getUserKey()).thenReturn(key);
        when(userManager.isSystemAdmin(key)).thenReturn(true);

        Response response = resource.listDeliveries(request, 25, 10);

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        verify(deliveryService).listDeliveries(10, 25);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getEntity();
        assertEquals(2, ((Map<?, ?>) payload.get("ackStats")).get("pendingCount"));
    }

    @Test
    public void acknowledgeDeliveryRequiresAdmin() {
        when(userManager.getRemoteUser(request)).thenReturn(null);

        Response response = resource.acknowledgeDelivery(request, 1, new AutomationResource.AckRequest());

        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
        verifyNoInteractions(deliveryService);
    }

    @Test
    public void acknowledgeDeliveryInvokesService() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        UserKey key = new UserKey("admin");
        when(profile.getUserKey()).thenReturn(key);
        when(profile.getFullName()).thenReturn("Admin");
        when(userManager.isSystemAdmin(key)).thenReturn(true);
        AutomationResource.AckRequest payload = new AutomationResource.AckRequest();
        payload.note = "Investigated";

        Response response = resource.acknowledgeDelivery(request, 10, payload);

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        verify(deliveryService).acknowledge(eq(10), eq("admin"), eq("Admin"), eq("Investigated"));
    }

    @Test
    public void burstCreditsRequireAdmin() {
        when(userManager.getRemoteUser(request)).thenReturn(null);

        Response response = resource.listBurstCredits(request, false);

        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), response.getStatus());
        verifyNoInteractions(burstCreditService);
    }

    @Test
    public void burstCreditEndpointsInvokeService() {
        when(userManager.getRemoteUser(request)).thenReturn(profile);
        UserKey key = new UserKey("admin");
        when(profile.getUserKey()).thenReturn(key);
        when(profile.getFullName()).thenReturn("Admin");
        when(userManager.isSystemAdmin(key)).thenReturn(true);
        BurstCredit credit = new BurstCredit(
                5,
                GuardrailsRateLimitScope.REPOSITORY,
                "repo",
                5,
                0,
                5,
                System.currentTimeMillis(),
                System.currentTimeMillis() + 60000,
                true,
                "admin",
                "Admin",
                "spike",
                null,
                0L);
        when(burstCreditService.listCredits(false)).thenReturn(List.of(credit));
        when(burstCreditService.purgeExpired()).thenReturn(0);
        when(burstCreditService.grantCredit(any(), any(), anyInt(), anyLong(), any(), any(), any(), any()))
                .thenReturn(credit);
        when(burstCreditService.revokeCredit(eq(5), any())).thenReturn(true);

        Response listResponse = resource.listBurstCredits(request, false);
        assertEquals(Response.Status.OK.getStatusCode(), listResponse.getStatus());

        AutomationResource.BurstCreditRequest grant = new AutomationResource.BurstCreditRequest();
        grant.scope = "repository";
        grant.projectKey = "PRJ";
        grant.repositorySlug = "repo";
        grant.tokens = 5;
        grant.durationMinutes = 30;
        grant.reason = "spike";

        Response grantResponse = resource.grantBurstCredit(request, grant);
        assertEquals(Response.Status.OK.getStatusCode(), grantResponse.getStatus());

        Response revokeResponse = resource.revokeBurstCredit(request, 5, new AutomationResource.BurstCreditRevokeRequest());
        assertEquals(Response.Status.NO_CONTENT.getStatusCode(), revokeResponse.getStatus());
        verify(burstCreditService).revokeCredit(eq(5), any());
    }
}
