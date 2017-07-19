package org.visallo.web.product.graph.routes;

import com.google.inject.Inject;
import com.v5analytics.webster.ParameterizedHandler;
import com.v5analytics.webster.annotations.Handle;
import com.v5analytics.webster.annotations.Required;
import org.json.JSONObject;
import org.vertexium.Authorizations;
import org.vertexium.Graph;
import org.vertexium.Vertex;
import org.visallo.core.exception.VisalloAccessDeniedException;
import org.visallo.core.exception.VisalloException;
import org.visallo.core.model.graph.GraphRepository;
import org.visallo.core.model.graph.GraphUpdateContext;
import org.visallo.core.model.user.AuthorizationRepository;
import org.visallo.core.model.user.UserRepository;
import org.visallo.core.model.workQueue.Priority;
import org.visallo.core.model.workQueue.WorkQueueRepository;
import org.visallo.core.model.workspace.Workspace;
import org.visallo.core.model.workspace.WorkspaceHelper;
import org.visallo.core.model.workspace.WorkspaceRepository;
import org.visallo.core.user.User;
import org.visallo.core.util.JSONUtil;
import org.visallo.core.util.VisalloLogger;
import org.visallo.core.util.VisalloLoggerFactory;
import org.visallo.web.VisalloResponse;
import org.visallo.web.clientapi.model.ClientApiSuccess;
import org.visallo.web.clientapi.model.ClientApiWorkspace;
import org.visallo.web.parameterProviders.ActiveWorkspaceId;
import org.visallo.web.parameterProviders.SourceGuid;
import org.visallo.web.product.graph.GraphWorkProduct;

import java.util.List;
import java.util.stream.Collectors;

public class UpdateVertices implements ParameterizedHandler {
    private static final VisalloLogger LOGGER = VisalloLoggerFactory.getLogger(UpdateVertices.class);

    private final Graph graph;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceHelper workspaceHelper;
    private final WorkQueueRepository workQueueRepository;
    private final AuthorizationRepository authorizationRepository;
    private final GraphRepository graphRepository;
    private final UserRepository userRepository;

    @Inject
    public UpdateVertices(
            Graph graph,
            WorkspaceRepository workspaceRepository,
            WorkspaceHelper workspaceHelper,
            WorkQueueRepository workQueueRepository,
            AuthorizationRepository authorizationRepository,
            GraphRepository graphRepository,
            UserRepository userRepository
    ) {
        this.graph = graph;
        this.workspaceRepository = workspaceRepository;
        this.workspaceHelper = workspaceHelper;
        this.workQueueRepository = workQueueRepository;
        this.authorizationRepository = authorizationRepository;
        this.graphRepository = graphRepository;
        this.userRepository = userRepository;
    }

    @Handle
    public ClientApiSuccess handle(
            @Required(name = "updates") String updates,
            @Required(name = "productId") String productId,
            @ActiveWorkspaceId String workspaceId,
            @SourceGuid String sourceGuid,
            User user
    ) throws Exception {
        JSONObject updateVertices = new JSONObject(updates);

        if (!workspaceRepository.hasWritePermissions(workspaceId, user)) {
            throw new VisalloAccessDeniedException(
                    "user " + user.getUserId() + " does not have write access to workspace " + workspaceId,
                    user,
                    workspaceId
            );
        }

        Authorizations authorizations = authorizationRepository.getGraphAuthorizations(
                user,
                WorkspaceRepository.VISIBILITY_STRING,
                workspaceId
        );

        List<String> vertices = JSONUtil.toStringList(updateVertices.names());
        vertices = vertices.stream()
                .filter(id -> !((JSONObject) updateVertices.get(id)).has("children"))
                .collect(Collectors.toList());
        workspaceHelper.updateEntitiesOnWorkspace(
                workspaceId,
                vertices,
                user
        );

        try (GraphUpdateContext ctx = graphRepository.beginGraphUpdate(Priority.HIGH, user, authorizations)) {
            GraphWorkProduct graphWorkProduct = new GraphWorkProduct(authorizationRepository, graphRepository, userRepository);
            Vertex productVertex = graph.getVertex(productId, authorizations);

            graphWorkProduct.updateVertices(ctx, productVertex, updateVertices, user, WorkspaceRepository.VISIBILITY.getVisibility(), authorizations);
        } catch(Exception e) {
            throw new VisalloException("Could not update vertices in product: " + productId);
        }

        Workspace workspace = workspaceRepository.findById(workspaceId, user);
        ClientApiWorkspace clientApiWorkspace = workspaceRepository.toClientApi(workspace, user, authorizations);

        workQueueRepository.broadcastWorkProductChange(productId, clientApiWorkspace, user, sourceGuid);

        return VisalloResponse.SUCCESS;
    }
}
