package org.visallo.web.routes.ontology;

import com.google.inject.Inject;
import com.v5analytics.webster.ParameterizedHandler;
import com.v5analytics.webster.annotations.Handle;
import com.v5analytics.webster.annotations.Optional;
import com.v5analytics.webster.annotations.Required;
import org.vertexium.Authorizations;
import org.visallo.core.model.ontology.*;
import org.visallo.core.model.workQueue.WorkQueueRepository;
import org.visallo.core.user.User;
import org.visallo.web.clientapi.model.ClientApiOntology;
import org.visallo.web.parameterProviders.ActiveWorkspaceId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class OntologyRealtionshipSave implements ParameterizedHandler {
    private final OntologyRepository ontologyRepository;
    private final WorkQueueRepository workQueueRepository;

    @Inject
    public OntologyRealtionshipSave(
            final OntologyRepository ontologyRepository,
            final WorkQueueRepository workQueueRepository
    ) {
        this.ontologyRepository = ontologyRepository;
        this.workQueueRepository = workQueueRepository;
    }

    @Handle
    public ClientApiOntology.Relationship handle(
            @Required(name = "displayName", allowEmpty = false) String displayName,
            @Required(name = "sourceIris[]", allowEmpty = false) String[] sourceIris,
            @Required(name = "targetIris[]", allowEmpty = false) String[] targetIris,
            @Optional(name = "parentIri", allowEmpty = false) String parentIri,
            @Optional(name = "iri", allowEmpty = false) String relationshipIri,
            @ActiveWorkspaceId String workspaceId,
            Authorizations authorizations,
            User user) throws Exception {


        List<Concept> domainConcepts = sourceIris == null ? new ArrayList<>() : Arrays.stream(sourceIris)
                .map(iri -> ontologyRepository.getConceptByIRI(iri, workspaceId))
                .collect(Collectors.toList());

        List<Concept> rangeConcepts = targetIris == null ? new ArrayList<>() : Arrays.stream(targetIris)
                .map(iri -> ontologyRepository.getConceptByIRI(iri, workspaceId))
                .collect(Collectors.toList());


        if (relationshipIri == null) {
            relationshipIri = ontologyRepository.generateDynamicIri(Relationship.class, displayName, workspaceId);
        }

        Relationship parent = null;
        if (parentIri != null) {
            parent = ontologyRepository.getRelationshipByIRI(parentIri, workspaceId);
        }

        Relationship relationship = ontologyRepository.getOrCreateRelationshipType(parent, domainConcepts, rangeConcepts, relationshipIri, displayName, false, user, workspaceId);
        relationship.setProperty(OntologyProperties.DISPLAY_NAME.getPropertyName(), displayName, authorizations);

        ontologyRepository.clearCache(workspaceId);
        workQueueRepository.pushOntologyRelationshipsChange(workspaceId, relationship.getId());

        return relationship.toClientApi();
    }

}
