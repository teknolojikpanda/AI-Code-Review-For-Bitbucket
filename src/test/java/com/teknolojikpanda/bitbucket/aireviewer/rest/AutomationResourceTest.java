package com.teknolojikpanda.bitbucket.aireviewer.rest;

import com.atlassian.sal.api.user.UserKey;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsAlertChannelService;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsAlertChannelService.Channel;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsAlertDeliveryService;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsAlertDeliveryService.Delivery;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class AutomationResourceTest {

    private UserManager userManager;
    private ReviewSchedulerStateService schedulerStateService;
    private GuardrailsAlertChannelService channelService;
    private GuardrailsAlertDeliveryService deliveryService;
    private AutomationResource resource;
    private HttpServletRequest request;
    private UserProfile profile;

    @Before
    public void setUp() {
        userManager = mock(UserManager.class);
        schedulerStateService = mock(ReviewSchedulerStateService.class);
        channelService = mock(GuardrailsAlertChannelService.class);
        deliveryService = mock(GuardrailsAlertDeliveryService.class);
        resource = new AutomationResource(userManager, schedulerStateService, channelService, deliveryService);
        request = mock(HttpServletRequest.class);
        profile = mock(UserProfile.class);
        Channel channel = new Channel(1, "https://example", "Ops Pager", true, 0L, 0L);
        Page<Channel> page = new Page<>(List.of(channel), 1, 50, 0);
        when(channelService.listChannels(anyInt(), anyInt())).thenReturn(page);
        when(channelService.sendTestAlert(anyInt())).thenReturn(true);
        Delivery delivery = new Delivery(42, 1, "https://example", "Ops Pager", System.currentTimeMillis(),
                true, false, 200, "{}", null, false, null, null, 0L, null);
        Page<Delivery> deliveryPage = new Page<>(List.of(delivery), 1, 50, 0);
        when(deliveryService.listDeliveries(anyInt(), anyInt())).thenReturn(deliveryPage);
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
        Channel channel = new Channel(1, "https://example", "Ops Pager", true, 0L, 0L);
        when(channelService.listChannels(anyInt(), anyInt())).thenReturn(new Page<>(List.of(channel), 1, 50, 0));
        when(channelService.createChannel(anyString(), anyString(), anyBoolean())).thenReturn(channel);
        when(channelService.updateChannel(1, "Ops Pager", true)).thenReturn(channel);

        Response list = resource.listChannels(request, null, null);
        assertEquals(Response.Status.OK.getStatusCode(), list.getStatus());
        verify(channelService).listChannels(0, 50);

        AutomationResource.ChannelRequest body = new AutomationResource.ChannelRequest();
        body.url = "https://example";
        body.description = "Ops Pager";
        body.enabled = true;

        Response create = resource.createChannel(request, body);
        assertEquals(Response.Status.OK.getStatusCode(), create.getStatus());

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
}
