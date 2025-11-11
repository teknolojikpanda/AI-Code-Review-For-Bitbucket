package com.teknolojikpanda.bitbucket.aireviewer.rest;

import com.atlassian.sal.api.user.UserKey;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsAlertChannelService;
import com.teknolojikpanda.bitbucket.aireviewer.service.GuardrailsAlertChannelService.Channel;
import com.teknolojikpanda.bitbucket.aireviewer.service.ReviewSchedulerStateService;
import org.junit.Before;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class AutomationResourceTest {

    private UserManager userManager;
    private ReviewSchedulerStateService schedulerStateService;
    private GuardrailsAlertChannelService channelService;
    private AutomationResource resource;
    private HttpServletRequest request;
    private UserProfile profile;

    @Before
    public void setUp() {
        userManager = mock(UserManager.class);
        schedulerStateService = mock(ReviewSchedulerStateService.class);
        channelService = mock(GuardrailsAlertChannelService.class);
        resource = new AutomationResource(userManager, schedulerStateService, channelService);
        request = mock(HttpServletRequest.class);
        profile = mock(UserProfile.class);
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

        Response response = resource.listChannels(request);

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
        when(channelService.listChannels()).thenReturn(List.of(channel));
        when(channelService.createChannel(anyString(), anyString(), anyBoolean())).thenReturn(channel);
        when(channelService.updateChannel(1, "Ops Pager", true)).thenReturn(channel);

        Response list = resource.listChannels(request);
        assertEquals(Response.Status.OK.getStatusCode(), list.getStatus());

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
}
