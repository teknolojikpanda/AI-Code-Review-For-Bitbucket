package com.example.bitbucket.aireviewer.rest;

import com.atlassian.bitbucket.permission.Permission;
import com.atlassian.bitbucket.permission.PermissionService;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.repository.RepositoryService;
import com.atlassian.bitbucket.user.ApplicationUser;
import com.atlassian.bitbucket.user.UserService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.sal.api.user.UserProfile;
import com.example.bitbucket.aireviewer.service.AIReviewerConfigService;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Path("/config/repositories")
@Named
public class RepoConfigResource {

    private final UserManager userManager;
    private final RepositoryService repositoryService;
    private final PermissionService permissionService;
    private final UserService userService;
    private final AIReviewerConfigService configService;

    @Inject
    public RepoConfigResource(@ComponentImport UserManager userManager,
                              @ComponentImport RepositoryService repositoryService,
                              @ComponentImport PermissionService permissionService,
                              @ComponentImport UserService userService,
                              AIReviewerConfigService configService) {
        this.userManager = Objects.requireNonNull(userManager, "userManager");
        this.repositoryService = Objects.requireNonNull(repositoryService, "repositoryService");
        this.permissionService = Objects.requireNonNull(permissionService, "permissionService");
        this.userService = Objects.requireNonNull(userService, "userService");
        this.configService = Objects.requireNonNull(configService, "configService");
    }

    @GET
    @Path("/{projectKey}/{repositorySlug}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getRepositoryConfiguration(@Context HttpServletRequest request,
                                               @PathParam("projectKey") String projectKey,
                                               @PathParam("repositorySlug") String repositorySlug) {
        AccessContext context = requireRepositoryAdmin(request, projectKey, repositorySlug);
        if (!context.allowed) {
            return context.response;
        }

        Map<String, Object> payload = configService.getRepositoryConfiguration(projectKey, repositorySlug);
        return Response.ok(payload).build();
    }

    @GET
    @Path("/id/{repositoryId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getRepositoryConfigurationById(@Context HttpServletRequest request,
                                                   @PathParam("repositoryId") int repositoryId) {
        Repository repository = repositoryService.getById(repositoryId);
        if (repository == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(error("Repository not found"))
                    .build();
        }
        return getRepositoryConfiguration(request,
                repository.getProject().getKey(),
                repository.getSlug());
    }

    @PUT
    @Path("/{projectKey}/{repositorySlug}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateRepositoryConfiguration(@Context HttpServletRequest request,
                                                  @PathParam("projectKey") String projectKey,
                                                  @PathParam("repositorySlug") String repositorySlug,
                                                  Map<String, Object> overrides) {
        AccessContext context = requireRepositoryAdmin(request, projectKey, repositorySlug);
        if (!context.allowed) {
            return context.response;
        }
        if (overrides == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(error("Override payload is required"))
                    .build();
        }

        configService.updateRepositoryConfiguration(
                projectKey,
                repositorySlug,
                overrides,
                context.user != null ? context.user.getSlug() : null);

        Map<String, Object> payload = configService.getRepositoryConfiguration(projectKey, repositorySlug);
        return Response.ok(payload).build();
    }

    @PUT
    @Path("/id/{repositoryId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response updateRepositoryConfigurationById(@Context HttpServletRequest request,
                                                      @PathParam("repositoryId") int repositoryId,
                                                      Map<String, Object> overrides) {
        Repository repository = repositoryService.getById(repositoryId);
        if (repository == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(error("Repository not found"))
                    .build();
        }
        return updateRepositoryConfiguration(request,
                repository.getProject().getKey(),
                repository.getSlug(),
                overrides);
    }

    @DELETE
    @Path("/{projectKey}/{repositorySlug}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response clearRepositoryConfiguration(@Context HttpServletRequest request,
                                                 @PathParam("projectKey") String projectKey,
                                                 @PathParam("repositorySlug") String repositorySlug) {
        AccessContext context = requireRepositoryAdmin(request, projectKey, repositorySlug);
        if (!context.allowed) {
            return context.response;
        }

        configService.clearRepositoryConfiguration(projectKey, repositorySlug);
        Map<String, Object> payload = configService.getRepositoryConfiguration(projectKey, repositorySlug);
        return Response.ok(payload).build();
    }

    @DELETE
    @Path("/id/{repositoryId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response clearRepositoryConfigurationById(@Context HttpServletRequest request,
                                                     @PathParam("repositoryId") int repositoryId) {
        Repository repository = repositoryService.getById(repositoryId);
        if (repository == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(error("Repository not found"))
                    .build();
        }
        return clearRepositoryConfiguration(request,
                repository.getProject().getKey(),
                repository.getSlug());
    }

    private AccessContext requireRepositoryAdmin(HttpServletRequest request,
                                                 String projectKey,
                                                 String repositorySlug) {
        UserProfile profile = userManager.getRemoteUser(request);
        if (profile == null) {
            return AccessContext.denied(Response.status(Response.Status.UNAUTHORIZED)
                    .entity(error("Authentication required")).build());
        }

        Repository repository = repositoryService.getBySlug(projectKey, repositorySlug);
        if (repository == null) {
            return AccessContext.denied(Response.status(Response.Status.NOT_FOUND)
                    .entity(error("Repository not found")).build());
        }

        ApplicationUser user = userService.getUserBySlug(profile.getUsername());
        if (user == null) {
            return AccessContext.denied(Response.status(Response.Status.UNAUTHORIZED)
                    .entity(error("Unable to resolve current user")).build());
        }

        boolean hasRepoAdmin = permissionService.hasRepositoryPermission(user, repository, Permission.REPO_ADMIN);
        boolean hasProjectAdmin = permissionService.hasProjectPermission(user, repository.getProject(), Permission.PROJECT_ADMIN);
        boolean hasGlobalAdmin = permissionService.hasGlobalPermission(user, Permission.ADMIN);

        if (!hasRepoAdmin && !hasProjectAdmin && !hasGlobalAdmin) {
            return AccessContext.denied(Response.status(Response.Status.FORBIDDEN)
                    .entity(error("Access denied. Repository admin privileges required.")).build());
        }

        return AccessContext.allowed(repository, user);
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("error", message);
        return map;
    }

    private static final class AccessContext {
        final boolean allowed;
        final Repository repository;
        final ApplicationUser user;
        final Response response;

        private AccessContext(boolean allowed,
                              Repository repository,
                              ApplicationUser user,
                              Response response) {
            this.allowed = allowed;
            this.repository = repository;
            this.user = user;
            this.response = response;
        }

        static AccessContext denied(Response response) {
            return new AccessContext(false, null, null, response);
        }

        static AccessContext allowed(Repository repository, ApplicationUser user) {
            return new AccessContext(true, repository, user, null);
        }
    }
}
