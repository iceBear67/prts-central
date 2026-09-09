package io.ib67.prts.notification.resource;

import io.ib67.prts.Pages;
import io.ib67.prts.Perm;
import io.ib67.prts.auth.RequirePermission;
import io.ib67.prts.dto.NotificationView;
import io.ib67.prts.dto.request.CreateNotificationRequest;
import io.ib67.prts.notification.NotificationService;
import io.ib67.prts.notification.entity.Notification;
import io.ib67.prts.user.UserContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

/**
 * REST endpoint over the messages left for the calling user.
 */
@Path("/notification")
@Produces(MediaType.APPLICATION_JSON)
public class NotificationResource {

    private static final int MAX_PAGE_SIZE = 50;

    @Inject
    NotificationService notificationService;
    @Inject
    UserContext userContext;

    @GET
    @Transactional
    public List<NotificationView> listNotifications(
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("length") Integer length) {
        var window = Pages.clampLength(length, MAX_PAGE_SIZE);
        return Notification.listByRecipient(
                        userContext.require().getId(), Pages.clampOffset(offset, window), window).stream()
                .map(NotificationView::of)
                .toList();
    }

    @PUT
    @Path("/{notificationId}/read")
    public void markRead(@PathParam("notificationId") UUID notificationId) {
        notificationService.setRead(userContext.require().getId(), notificationId, true);
    }

    @DELETE
    @Path("/{notificationId}/read")
    public void markUnread(@PathParam("notificationId") UUID notificationId) {
        notificationService.setRead(userContext.require().getId(), notificationId, false);
    }

    /** Leaves a message for everyone who can log in. */
    @POST
    @Path("/broadcast")
    @Consumes(MediaType.APPLICATION_JSON)
    @RequirePermission(Perm.ADMIN_OF_ALL)
    public void broadcast(
            @NotNull(message = "a request body is required") @Valid CreateNotificationRequest request) {
        notificationService.broadcast(request.from(), request.title(), request.content());
    }
}
