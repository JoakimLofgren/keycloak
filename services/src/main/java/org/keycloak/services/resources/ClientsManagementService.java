package org.keycloak.services.resources;

import org.jboss.logging.Logger;
import org.jboss.resteasy.spi.BadRequestException;
import org.jboss.resteasy.spi.HttpRequest;
import org.jboss.resteasy.spi.UnauthorizedException;
import org.keycloak.ClientConnection;
import org.keycloak.OAuth2Constants;
import org.keycloak.constants.AdapterConstants;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.protocol.oidc.utils.AuthorizeClientUtil;
import org.keycloak.services.ForbiddenException;
import org.keycloak.util.Time;

import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ext.Providers;
import java.util.HashMap;
import java.util.Map;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class ClientsManagementService {

    protected static final Logger logger = Logger.getLogger(ClientsManagementService.class);

    private RealmModel realm;

    private EventBuilder event;

    @Context
    private HttpRequest request;

    @Context
    protected HttpHeaders headers;

    @Context
    private UriInfo uriInfo;

    @Context
    private ClientConnection clientConnection;

    @Context
    protected Providers providers;

    @Context
    protected KeycloakSession session;

    public ClientsManagementService(RealmModel realm, EventBuilder event) {
        this.realm = realm;
        this.event = event;
    }

    public static UriBuilder clientsManagementBaseUrl(UriBuilder baseUriBuilder) {
        return baseUriBuilder.path(RealmsResource.class).path(RealmsResource.class, "getClientsManagementService");
    }

    public static UriBuilder registerNodeUrl(UriBuilder baseUriBuilder) {
        UriBuilder uriBuilder = clientsManagementBaseUrl(baseUriBuilder);
        return uriBuilder.path(ClientsManagementService.class, "registerNode");
    }

    public static UriBuilder unregisterNodeUrl(UriBuilder baseUriBuilder) {
        UriBuilder uriBuilder = clientsManagementBaseUrl(baseUriBuilder);
        return uriBuilder.path(ClientsManagementService.class, "unregisterNode");
    }

    /**
     * URL invoked by adapter to register new client cluster node. Each application cluster node will invoke this URL once it joins cluster
     *
     * @param authorizationHeader
     * @param formData
     * @return
     */
    @Path("register-node")
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Response registerNode(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorizationHeader, final MultivaluedMap<String, String> formData) {
        if (!checkSsl()) {
            throw new ForbiddenException("HTTPS required");
        }

        event.event(EventType.REGISTER_NODE);

        if (!realm.isEnabled()) {
            event.error(Errors.REALM_DISABLED);
            throw new UnauthorizedException("Realm not enabled");
        }

        ClientModel client = authorizeClient();
        String nodeHost = getClientClusterHost(formData);

        event.client(client).detail(Details.NODE_HOST, nodeHost);
        logger.debugf("Registering cluster host '%s' for client '%s'", nodeHost, client.getClientId());

        client.registerNode(nodeHost, Time.currentTime());

        event.success();

        return Response.noContent().build();
    }


    /**
     * URL invoked by adapter to register new client cluster node. Each application cluster node will invoke this URL once it joins cluster
     *
     * @param authorizationHeader
     * @param formData
     * @return
     */
    @Path("unregister-node")
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Response unregisterNode(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorizationHeader, final MultivaluedMap<String, String> formData) {
        if (!checkSsl()) {
            throw new ForbiddenException("HTTPS required");
        }

        event.event(EventType.UNREGISTER_NODE);

        if (!realm.isEnabled()) {
            event.error(Errors.REALM_DISABLED);
            throw new UnauthorizedException("Realm not enabled");
        }

        ClientModel client = authorizeClient();
        String nodeHost = getClientClusterHost(formData);

        event.client(client).detail(Details.NODE_HOST, nodeHost);
        logger.debugf("Unregistering cluster host '%s' for client '%s'", nodeHost, client.getClientId());

        client.unregisterNode(nodeHost);

        event.success();

        return Response.noContent().build();
    }

    protected ClientModel authorizeClient() {
        ClientModel client = AuthorizeClientUtil.authorizeClient(session, event, realm).getClient();

        if (client.isPublicClient()) {
            Map<String, String> error = new HashMap<String, String>();
            error.put(OAuth2Constants.ERROR, "invalid_client");
            error.put(OAuth2Constants.ERROR_DESCRIPTION, "Public clients not allowed");
            event.error(Errors.INVALID_CLIENT);
            throw new BadRequestException("Public clients not allowed", javax.ws.rs.core.Response.status(javax.ws.rs.core.Response.Status.BAD_REQUEST).entity(error).type(MediaType.APPLICATION_JSON_TYPE).build());
        }

        return client;
    }

    protected String getClientClusterHost(MultivaluedMap<String, String> formData) {
        String clientClusterHost = formData.getFirst(AdapterConstants.CLIENT_CLUSTER_HOST);
        if (clientClusterHost == null || clientClusterHost.length() == 0) {
            Map<String, String> error = new HashMap<String, String>();
            error.put(OAuth2Constants.ERROR, "invalid_request");
            error.put(OAuth2Constants.ERROR_DESCRIPTION, "Client cluster host not specified");
            event.error(Errors.INVALID_CODE);
            throw new BadRequestException("Cluster host not specified", javax.ws.rs.core.Response.status(javax.ws.rs.core.Response.Status.BAD_REQUEST).entity(error).type(MediaType.APPLICATION_JSON_TYPE).build());
        }

        return clientClusterHost;
    }



    private boolean checkSsl() {
        if (uriInfo.getBaseUri().getScheme().equals("https")) {
            return true;
        } else {
            return !realm.getSslRequired().isRequired(clientConnection);
        }
    }
}
