package org.visallo.vertexium.model.ontology;

import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.hash.Hashing;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.semanticweb.owlapi.io.OWLOntologyDocumentSource;
import org.semanticweb.owlapi.io.ReaderDocumentSource;
import org.semanticweb.owlapi.model.*;
import org.vertexium.*;
import org.vertexium.mutation.ExistingElementMutation;
import org.vertexium.property.StreamingPropertyValue;
import org.vertexium.util.ConvertingIterable;
import org.visallo.core.bootstrap.InjectHelper;
import org.visallo.core.config.Configuration;
import org.visallo.core.exception.VisalloAccessDeniedException;
import org.visallo.core.exception.VisalloException;
import org.visallo.core.model.graph.GraphRepository;
import org.visallo.core.model.graph.GraphUpdateContext;
import org.visallo.core.model.lock.LockRepository;
import org.visallo.core.model.ontology.*;
import org.visallo.core.model.properties.VisalloProperties;
import org.visallo.core.model.user.AuthorizationRepository;
import org.visallo.core.model.user.GraphAuthorizationRepository;
import org.visallo.core.model.user.PrivilegeRepository;
import org.visallo.core.model.workQueue.Priority;
import org.visallo.core.model.workspace.WorkspaceProperties;
import org.visallo.core.model.workspace.WorkspaceRepository;
import org.visallo.core.security.VisibilityTranslator;
import org.visallo.core.user.User;
import org.visallo.core.util.JSONUtil;
import org.visallo.core.util.TimingCallable;
import org.visallo.core.util.VisalloLogger;
import org.visallo.core.util.VisalloLoggerFactory;
import org.visallo.web.clientapi.model.ClientApiOntology;
import org.visallo.web.clientapi.model.Privilege;
import org.visallo.web.clientapi.model.PropertyType;
import org.visallo.web.clientapi.model.VisibilityJson;

import javax.annotation.Nullable;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;

@Singleton
public class VertexiumOntologyRepository extends OntologyRepositoryBase {
    private static final VisalloLogger LOGGER = VisalloLoggerFactory.getLogger(VertexiumOntologyRepository.class);
    public static final String ID_PREFIX = "ontology_";
    public static final String ID_PREFIX_PROPERTY = ID_PREFIX + "prop_";
    public static final String ID_PREFIX_RELATIONSHIP = ID_PREFIX + "rel_";
    public static final String ID_PREFIX_CONCEPT = ID_PREFIX + "concept_";
    private final Graph graph;
    private final GraphRepository graphRepository;
    private final VisibilityTranslator visibilityTranslator;
    private AuthorizationRepository authorizationRepository;
    private PrivilegeRepository privilegeRepository;
    private Authorizations authorizations;
    private WorkspaceRepository workspaceRepository;
    protected Cache<String, List<Concept>> allConceptsWithPropertiesCache = CacheBuilder.newBuilder()
            .expireAfterWrite(15, TimeUnit.HOURS)
            .build();
    protected Cache<String, List<OntologyProperty>> allPropertiesCache = CacheBuilder.newBuilder()
            .expireAfterWrite(15, TimeUnit.HOURS)
            .build();
    protected Cache<String, List<Relationship>> relationshipLabelsCache = CacheBuilder.newBuilder()
            .expireAfterWrite(15, TimeUnit.HOURS)
            .build();
    protected Cache<String, ClientApiOntology> clientApiCache = CacheBuilder.newBuilder()
            .expireAfterWrite(15, TimeUnit.HOURS)
            .build();


    @Inject
    public VertexiumOntologyRepository(
            Graph graph,
            GraphRepository graphRepository,
            VisibilityTranslator visibilityTranslator,
            Configuration config,
            GraphAuthorizationRepository graphAuthorizationRepository,
            LockRepository lockRepository
    ) throws Exception {
        super(config, lockRepository);
        try {
            this.graph = graph;
            this.graphRepository = graphRepository;
            this.visibilityTranslator = visibilityTranslator;

            graphAuthorizationRepository.addAuthorizationToGraph(VISIBILITY_STRING);

            defineRequiredProperties(graph);

            Set<String> authorizationsSet = new HashSet<>();
            authorizationsSet.add(VISIBILITY_STRING);
            this.authorizations = graph.createAuthorizations(authorizationsSet);

            loadOntologies(config, authorizations);
        } catch (Exception ex) {
            LOGGER.error("Could not initialize: %s", this.getClass().getName(), ex);
            throw ex;
        }
    }

    private AuthorizationRepository getAuthorizationRepository() {
        if (authorizationRepository == null) {
            authorizationRepository = InjectHelper.getInstance(AuthorizationRepository.class);
        }
        return authorizationRepository;
    }

    private void defineRequiredProperties(Graph graph) {
        if (!graph.isPropertyDefined(VisalloProperties.CONCEPT_TYPE.getPropertyName())) {
            graph.defineProperty(VisalloProperties.CONCEPT_TYPE.getPropertyName())
                    .dataType(String.class)
                    .textIndexHint(TextIndexHint.EXACT_MATCH)
                    .define();
        }

        if (!graph.isPropertyDefined(OntologyProperties.ONTOLOGY_TITLE.getPropertyName())) {
            graph.defineProperty(OntologyProperties.ONTOLOGY_TITLE.getPropertyName())
                    .dataType(String.class)
                    .textIndexHint(TextIndexHint.EXACT_MATCH)
                    .define();
        }

        if (!graph.isPropertyDefined(OntologyProperties.DISPLAY_NAME.getPropertyName())) {
            graph.defineProperty(OntologyProperties.DISPLAY_NAME.getPropertyName())
                    .dataType(String.class)
                    .textIndexHint(TextIndexHint.EXACT_MATCH)
                    .define();
        }

        if (!graph.isPropertyDefined(OntologyProperties.TITLE_FORMULA.getPropertyName())) {
            graph.defineProperty(OntologyProperties.TITLE_FORMULA.getPropertyName())
                    .dataType(String.class)
                    .textIndexHint(TextIndexHint.NONE)
                    .define();
        }

        if (!graph.isPropertyDefined(OntologyProperties.SUBTITLE_FORMULA.getPropertyName())) {
            graph.defineProperty(OntologyProperties.SUBTITLE_FORMULA.getPropertyName())
                    .dataType(String.class)
                    .textIndexHint(TextIndexHint.NONE)
                    .define();
        }

        if (!graph.isPropertyDefined(OntologyProperties.TIME_FORMULA.getPropertyName())) {
            graph.defineProperty(OntologyProperties.TIME_FORMULA.getPropertyName())
                    .dataType(String.class)
                    .textIndexHint(TextIndexHint.NONE)
                    .define();
        }

        if (!graph.isPropertyDefined(OntologyProperties.GLYPH_ICON.getPropertyName())) {
            graph.defineProperty(OntologyProperties.GLYPH_ICON.getPropertyName())
                    .dataType(byte[].class)
                    .textIndexHint(TextIndexHint.NONE)
                    .define();
        }

        if (!graph.isPropertyDefined(OntologyProperties.DATA_TYPE.getPropertyName())) {
            graph.defineProperty(OntologyProperties.DATA_TYPE.getPropertyName())
                    .dataType(String.class)
                    .textIndexHint(TextIndexHint.EXACT_MATCH)
                    .define();
        }

        if (!graph.isPropertyDefined(OntologyProperties.USER_VISIBLE.getPropertyName())) {
            graph.defineProperty(OntologyProperties.USER_VISIBLE.getPropertyName())
                    .dataType(Boolean.TYPE)
                    .define();
        }

        if (!graph.isPropertyDefined(OntologyProperties.SEARCHABLE.getPropertyName())) {
            graph.defineProperty(OntologyProperties.SEARCHABLE.getPropertyName())
                    .dataType(Boolean.TYPE)
                    .define();
        }

        if (!graph.isPropertyDefined(OntologyProperties.SORTABLE.getPropertyName())) {
            graph.defineProperty(OntologyProperties.SORTABLE.getPropertyName())
                    .dataType(Boolean.TYPE)
                    .define();
        }

        if (!graph.isPropertyDefined(OntologyProperties.ADDABLE.getPropertyName())) {
            graph.defineProperty(OntologyProperties.ADDABLE.getPropertyName())
                    .dataType(Boolean.TYPE)
                    .define();
        }

        if (!graph.isPropertyDefined(OntologyProperties.ONTOLOGY_FILE.getPropertyName())) {
            graph.defineProperty(OntologyProperties.ONTOLOGY_FILE.getPropertyName())
                    .dataType(byte[].class)
                    .textIndexHint(TextIndexHint.NONE)
                    .define();
        }

        if (!graph.isPropertyDefined(OntologyProperties.ONTOLOGY_FILE_MD5.getPropertyName())) {
            graph.defineProperty(OntologyProperties.ONTOLOGY_FILE_MD5.getPropertyName())
                    .dataType(String.class)
                    .textIndexHint(TextIndexHint.NONE)
                    .define();
        }

        if (!graph.isPropertyDefined(OntologyProperties.DEPENDENT_PROPERTY_ORDER_PROPERTY_NAME.getPropertyName())) {
            graph.defineProperty(OntologyProperties.DEPENDENT_PROPERTY_ORDER_PROPERTY_NAME.getPropertyName())
                    .dataType(Integer.class)
                    .textIndexHint(TextIndexHint.NONE)
                    .define();
        }
    }

    @Override
    protected void importOntologyAnnotationProperty(OWLOntology o, OWLAnnotationProperty annotationProperty, File inDir, Authorizations authorizations) {
        super.importOntologyAnnotationProperty(o, annotationProperty, inDir, authorizations);

        String about = annotationProperty.getIRI().toString();
        LOGGER.debug("disabling index for annotation property: " + about);
        DefinePropertyBuilder definePropertyBuilder = graph.defineProperty(about);
        definePropertyBuilder.dataType(PropertyType.getTypeClass(PropertyType.STRING));
        definePropertyBuilder.textIndexHint(TextIndexHint.NONE);
        definePropertyBuilder.define();
    }

    @Override
    public ClientApiOntology getClientApiObject(User user, String workspaceId) {
        String key = cacheKey(workspaceId);
        ClientApiOntology o = this.clientApiCache.getIfPresent(key);
        if (o != null) {
            return o;
        }

        try {
            ClientApiOntology published = this.clientApiCache.get(cacheKey(null), new TimingCallable<ClientApiOntology>("getClientApiObject") {
                @Override
                protected ClientApiOntology callWithTime() throws Exception {
                    return VertexiumOntologyRepository.super.getClientApiObject(user, null);
                }
            });


            if (workspaceId != null) {
                Authorizations authorizations = getAuthorizationRepository().getGraphAuthorizations(user, workspaceId, WorkspaceRepository.VISIBILITY_STRING, VISIBILITY_STRING);
                Vertex workspace = graph.getVertex(workspaceId, authorizations);
                Iterable<Vertex> vertices = workspace.getVertices(Direction.OUT, WorkspaceProperties.WORKSPACE_TO_ONTOLOGY_RELATIONSHIP_IRI, authorizations);
                List<Concept> concepts = transformConcepts(vertices, user, workspaceId);
                ClientApiOntology workspaceOntology = published.merge(
                        concepts.stream().map(concept -> concept.toClientApi()).collect(Collectors.toList()),
                        // FIXME, find these 2
                        new ArrayList<>(),
                        new ArrayList<>()
                );

                if (workspaceOntology != null) {
                    this.clientApiCache.put(key, workspaceOntology);
                }

                return workspaceOntology;
            }

            return published;
        } catch (ExecutionException e) {
            throw new VisalloException("Unable to load published ontology", e);
        }
    }

    @Override
    public void clearCache() {
        LOGGER.info("clearing ontology cache");
        graph.flush();
        this.clientApiCache.invalidateAll();
        this.allConceptsWithPropertiesCache.invalidateAll();
        this.allPropertiesCache.invalidateAll();
        this.relationshipLabelsCache.invalidateAll();
    }

    @Override
    public void clearCache(String workspaceId) {
        checkNotNull(workspaceId, "Workspace should not be null");
        LOGGER.info("clearing ontology cache for workspace %s", workspaceId);
        graph.flush();
        this.clientApiCache.invalidate(workspaceId);
        this.allConceptsWithPropertiesCache.invalidate(workspaceId);
        this.allPropertiesCache.invalidate(workspaceId);
        this.relationshipLabelsCache.invalidate(workspaceId);
    }

    @Override
    protected void addEntityGlyphIconToEntityConcept(Concept entityConcept, byte[] rawImg) {
        StreamingPropertyValue raw = new StreamingPropertyValue(new ByteArrayInputStream(rawImg), byte[].class);
        raw.searchIndex(false);
        entityConcept.setProperty(OntologyProperties.GLYPH_ICON.getPropertyName(), raw, authorizations);
        graph.flush();
    }

    @Override
    public void storeOntologyFile(InputStream in, IRI documentIRI) {
        byte[] data;
        try {
            data = IOUtils.toByteArray(in);
        } catch (IOException ex) {
            throw new VisalloException("Could not read ontology input stream", ex);
        }
        String md5 = DigestUtils.md5Hex(data);
        StreamingPropertyValue value = new StreamingPropertyValue(new ByteArrayInputStream(data), byte[].class);
        value.searchIndex(false);
        Metadata metadata = new Metadata();
        Vertex rootConceptVertex = ((VertexiumConcept) getRootConcept()).getVertex();
        metadata.add("index", Iterables.size(OntologyProperties.ONTOLOGY_FILE.getProperties(rootConceptVertex)), VISIBILITY.getVisibility());
        OntologyProperties.ONTOLOGY_FILE.addPropertyValue(rootConceptVertex, documentIRI.toString(), value, metadata, VISIBILITY.getVisibility(), authorizations);
        OntologyProperties.ONTOLOGY_FILE_MD5.addPropertyValue(rootConceptVertex, documentIRI.toString(), md5, metadata, VISIBILITY.getVisibility(), authorizations);
        graph.flush();
    }

    @Override
    protected boolean hasFileChanged(IRI documentIRI, byte[] inFileData) {
        Vertex rootConceptVertex = ((VertexiumConcept) getRootConcept()).getVertex();
        String existingMd5 = OntologyProperties.ONTOLOGY_FILE_MD5.getPropertyValue(rootConceptVertex, documentIRI.toString());
        if (existingMd5 == null) {
            return true;
        }
        return !DigestUtils.md5Hex(inFileData).equals(existingMd5);
    }

    @Override
    public boolean isOntologyDefined(String iri) {
        Vertex rootConceptVertex = ((VertexiumConcept) getRootConcept()).getVertex();
        Property prop = OntologyProperties.ONTOLOGY_FILE.getProperty(rootConceptVertex, iri);
        return prop != null;
    }

    @Override
    public List<OWLOntology> loadOntologyFiles(OWLOntologyManager m, OWLOntologyLoaderConfiguration config, IRI excludedIRI) throws OWLOntologyCreationException, IOException {
        List<OWLOntology> loadedOntologies = new ArrayList<>();
        Iterable<Property> ontologyFiles = getOntologyFiles();
        for (Property ontologyFile : ontologyFiles) {
            IRI ontologyFileIRI = IRI.create(ontologyFile.getKey());
            if (excludedIRI != null && excludedIRI.equals(ontologyFileIRI)) {
                continue;
            }
            try (InputStream visalloBaseOntologyIn = ((StreamingPropertyValue) ontologyFile.getValue()).getInputStream()) {
                Reader visalloBaseOntologyReader = new InputStreamReader(visalloBaseOntologyIn);
                LOGGER.info("Loading existing ontology: %s", ontologyFile.getKey());
                OWLOntologyDocumentSource visalloBaseOntologySource = new ReaderDocumentSource(visalloBaseOntologyReader, ontologyFileIRI);
                try {
                    OWLOntology o = m.loadOntologyFromOntologyDocument(visalloBaseOntologySource, config);
                    loadedOntologies.add(o);
                } catch (UnloadableImportException ex) {
                    LOGGER.error("Could not load %s", ontologyFileIRI, ex);
                }
            }
        }
        return loadedOntologies;
    }

    private Iterable<Property> getOntologyFiles() {
        VertexiumConcept rootConcept = (VertexiumConcept) getRootConcept();
        checkNotNull(rootConcept, "Could not get root concept");
        Vertex rootConceptVertex = rootConcept.getVertex();
        checkNotNull(rootConceptVertex, "Could not get root concept vertex");

        List<Property> ontologyFiles = Lists.newArrayList(OntologyProperties.ONTOLOGY_FILE.getProperties(rootConceptVertex));
        ontologyFiles.sort((ontologyFile1, ontologyFile2) -> {
            Integer index1 = (Integer) ontologyFile1.getMetadata().getValue("index");
            checkNotNull(index1, "Could not find metadata (1) 'index' on " + ontologyFile1);
            Integer index2 = (Integer) ontologyFile2.getMetadata().getValue("index");
            checkNotNull(index2, "Could not find metadata (2) 'index' on " + ontologyFile2);
            return index1.compareTo(index2);
        });
        return ontologyFiles;
    }


    @Override
    public Iterable<Relationship> getRelationships(Iterable<String> ids, User user, String workspaceId) {
        return transformRelationships(graph.getVertices(ids, getAuthorizations(user, workspaceId)), user, workspaceId);
    }

    @Override
    public Iterable<Relationship> getRelationships(User user, String workspaceId) {
        try {
            return relationshipLabelsCache.get(cacheKey(workspaceId), new TimingCallable<List<Relationship>>("getRelationships") {
                @Override
                public List<Relationship> callWithTime() throws Exception {
                    Iterable<Vertex> vertices = graph.getVerticesWithPrefix(ID_PREFIX_RELATIONSHIP, getAuthorizations(user, workspaceId));
                    vertices = Iterables.filter(vertices, vertex -> VisalloProperties.CONCEPT_TYPE.getPropertyValue(vertex, "").equals(TYPE_RELATIONSHIP));
                    return transformRelationships(vertices, user, workspaceId);
                }
            });
        } catch (ExecutionException e) {
            throw new VisalloException("Could not get relationship labels");
        }
    }

    private Relationship toVertexiumRelationship(Vertex relationshipVertex, User user, String workspaceId) {
        Iterable<Vertex> domainVertices = relationshipVertex.getVertices(Direction.IN, LabelName.HAS_EDGE.toString(), getAuthorizations(user, workspaceId));
        List<String> domainConceptIris = Lists.newArrayList(new ConvertingIterable<Vertex, String>(domainVertices) {
            @Override
            protected String convert(Vertex domainVertex) {
                return OntologyProperties.ONTOLOGY_TITLE.getPropertyValue(domainVertex);
            }
        });

        Iterable<Vertex> rangeVertices = relationshipVertex.getVertices(Direction.OUT, LabelName.HAS_EDGE.toString(), getAuthorizations(user, workspaceId));
        List<String> rangeConceptIris = Lists.newArrayList(new ConvertingIterable<Vertex, String>(rangeVertices) {
            @Override
            protected String convert(Vertex rangeVertex) {
                return OntologyProperties.ONTOLOGY_TITLE.getPropertyValue(rangeVertex);
            }
        });

        List<String> parentVertexIds = Lists.newArrayList(Iterables.transform(relationshipVertex.getVertices(Direction.OUT, LabelName.IS_A.toString(), getAuthorizations(user, workspaceId)), new Function<Vertex, String>() {
            @Override
            public String apply(Vertex parentRelationshipVertex) {
                return OntologyProperties.ONTOLOGY_TITLE.getPropertyValue(parentRelationshipVertex);
            }
        }));
        if (parentVertexIds.size() > 1) {
            throw new VisalloException("Too many parent relationships found for relationship " + relationshipVertex.getId());
        }
        String parentIRI = parentVertexIds.size() == 0 ? null : parentVertexIds.get(0);

        final List<String> inverseOfIRIs = getRelationshipInverseOfIRIs(relationshipVertex, user, workspaceId);
        List<OntologyProperty> properties = getPropertiesByVertexNoRecursion(relationshipVertex, user, workspaceId);
        return createRelationship(parentIRI, relationshipVertex, inverseOfIRIs, domainConceptIris, rangeConceptIris, properties);
    }

    private List<String> getRelationshipInverseOfIRIs(final Vertex vertex, User user, String workspaceId) {
        return Lists.newArrayList(new ConvertingIterable<Vertex, String>(vertex.getVertices(Direction.OUT, LabelName.INVERSE_OF.toString(), getAuthorizations(user, workspaceId))) {
            @Override
            protected String convert(Vertex inverseOfVertex) {
                return OntologyProperties.ONTOLOGY_TITLE.getPropertyValue(inverseOfVertex);
            }
        });
    }

    @Override
    public String getDisplayNameForLabel(String relationshipIRI, User user, String workspaceId) {
        String displayName = null;
        if (relationshipIRI != null && !relationshipIRI.trim().isEmpty()) {
            try {
                Relationship relationship = getRelationshipByIRI(relationshipIRI, user, workspaceId);
                if (relationship != null) {
                    displayName = relationship.getDisplayName();
                }
            } catch (IllegalArgumentException iae) {
                throw new IllegalStateException(
                        String.format("Found multiple vertices for relationship label \"%s\"", relationshipIRI),
                        iae
                );
            }
        }
        return displayName;
    }


    @Override
    public Iterable<OntologyProperty> getProperties(Iterable<String> ids, User user, String workspaceId) {
        return transformProperties(graph.getVertices(ids, getAuthorizations(user, workspaceId)), user, workspaceId);
    }

    @Override
    public Iterable<OntologyProperty> getProperties(User user, String workspaceId) {
        try {
            return allPropertiesCache.get(cacheKey(workspaceId), new TimingCallable<List<OntologyProperty>>("getProperties") {
                @Override
                public List<OntologyProperty> callWithTime() throws Exception {
                    Iterable<Vertex> vertices = graph.getVerticesWithPrefix(ID_PREFIX_PROPERTY, getAuthorizations(user, workspaceId));
                    vertices = Iterables.filter(vertices, vertex -> VisalloProperties.CONCEPT_TYPE.getPropertyValue(vertex, "").equals(TYPE_PROPERTY));
                    return transformProperties(vertices, user, workspaceId);
                }
            });
        } catch (ExecutionException e) {
            throw new VisalloException("Could not get properties", e);
        }
    }

    protected ImmutableList<String> getDependentPropertyIris(final Vertex vertex, User user, String workspaceId) {
        List<Edge> dependentProperties = Lists.newArrayList(vertex.getEdges(Direction.OUT, OntologyProperties.EDGE_LABEL_DEPENDENT_PROPERTY, getAuthorizations(user, workspaceId)));
        Collections.sort(dependentProperties, (e1, e2) -> {
            Integer o1 = OntologyProperties.DEPENDENT_PROPERTY_ORDER_PROPERTY_NAME.getPropertyValue(e1, 0);
            Integer o2 = OntologyProperties.DEPENDENT_PROPERTY_ORDER_PROPERTY_NAME.getPropertyValue(e2, 0);
            return Integer.compare(o1, o2);
        });
        return ImmutableList.copyOf(Iterables.transform(dependentProperties, new Function<Edge, String>() {
            @Nullable
            @Override
            public String apply(Edge e) {
                String propertyId = e.getOtherVertexId(vertex.getId());
                return propertyId.substring(VertexiumOntologyRepository.ID_PREFIX_PROPERTY.length());
            }
        }));
    }

    @Override
    public Iterable<Concept> getConceptsWithProperties(User user, String workspaceId) {
        try {
            return allConceptsWithPropertiesCache.get(cacheKey(workspaceId), new TimingCallable<List<Concept>>("getConceptsWithProperties") {
                @Override
                public List<Concept> callWithTime() throws Exception {
                    Authorizations authorizations = getAuthorizations(user, workspaceId);
                    if (workspaceId != null) {
                        authorizations = graph.createAuthorizations(authorizations, workspaceId);
                    }
                    Iterable<Vertex> vertices = graph.getVerticesWithPrefix(ID_PREFIX_CONCEPT, authorizations);
                    vertices = Iterables.filter(vertices, vertex -> VisalloProperties.CONCEPT_TYPE.getPropertyValue(vertex, "").equals(TYPE_CONCEPT));
                    return transformConcepts(vertices, user, workspaceId);
                }
            });
        } catch (ExecutionException e) {
            throw new VisalloException("could not get concepts with properties", e);
        }
    }

    @Override
    public Concept getRootConcept() {
        return getRootConcept(null, null);
    }

    @Override
    public Concept getRootConcept(User user, String workspaceId) {
        return getConceptByIRI(VertexiumOntologyRepository.ROOT_CONCEPT_IRI, user, workspaceId);
    }

    @Override
    public Concept getEntityConcept() {
        return getEntityConcept(null, null);
    }

    @Override
    public Concept getEntityConcept(User user, String workspaceId) {
        return getConceptByIRI(VertexiumOntologyRepository.ENTITY_CONCEPT_IRI, user, workspaceId);
    }

    @Override
    protected List<Concept> getChildConcepts(Concept concept, User user, String workspaceId) {
        Vertex conceptVertex = ((VertexiumConcept) concept).getVertex();
        return toConcepts(conceptVertex.getVertices(Direction.IN, LabelName.IS_A.toString(), getAuthorizations(user, workspaceId)));
    }

    @Override
    protected List<Relationship> getChildRelationships(Relationship relationship, User user, String workspaceId) {
        Vertex relationshipVertex = ((VertexiumRelationship) relationship).getVertex();
        return toRelationships(relationshipVertex.getVertices(Direction.IN, LabelName.IS_A.toString(), getAuthorizations(user, workspaceId)), user, workspaceId);
    }

    @Override
    public Concept getParentConcept(final Concept concept, User user, String workspaceId) {
        Vertex parentConceptVertex = getParentConceptVertex(((VertexiumConcept) concept).getVertex(), user, workspaceId);
        if (parentConceptVertex == null) {
            return null;
        }

        String parentIri = OntologyProperties.ONTOLOGY_TITLE.getPropertyValue(parentConceptVertex);

        return getConceptByIRI(parentIri);
    }

    private List<Concept> toConcepts(Iterable<Vertex> vertices) {
        ArrayList<Concept> concepts = new ArrayList<>();
        for (Vertex vertex : vertices) {
            concepts.add(createConcept(vertex));
        }
        return concepts;
    }

    private List<Relationship> toRelationships(Iterable<Vertex> vertices, User user, String workspaceId) {
        ArrayList<Relationship> relationships = new ArrayList<>();
        for (Vertex vertex : vertices) {
            relationships.add(toVertexiumRelationship(vertex, user, workspaceId));
        }
        return relationships;
    }

    private List<OntologyProperty> getPropertiesByVertexNoRecursion(Vertex vertex, User user, String workspaceId) {
        return Lists.newArrayList(new ConvertingIterable<Vertex, OntologyProperty>(vertex.getVertices(Direction.OUT, LabelName.HAS_PROPERTY.toString(), getAuthorizations(user, workspaceId))) {
            @Override
            protected OntologyProperty convert(Vertex o) {
                return createOntologyProperty(o, getDependentPropertyIris(o, user, workspaceId));
            }
        });
    }

    @Override
    public Iterable<Concept> getConcepts(Iterable<String> ids, User user, String workspaceId) {
        return transformConcepts(graph.getVertices(ids, getAuthorizations(user, workspaceId)), user, workspaceId);
    }

    @Override
    public Concept getOrCreateConcept(Concept parent, String conceptIRI, String displayName, File inDir) {
        return getOrCreateConcept(parent, conceptIRI, displayName, inDir, true);
    }

    @Override
    public Concept getOrCreateConcept(Concept parent, String conceptIRI, String displayName, File inDir, User user, String workspaceId) {
        return getOrCreateConcept(parent, conceptIRI, displayName, null, false, user, workspaceId);
    }

    @Override
    public Concept getOrCreateConcept(Concept parent, String conceptIRI, String displayName, File inDir, boolean deleteChangeableProperties) {
        return getOrCreateConcept(parent, conceptIRI, displayName, inDir, deleteChangeableProperties, null, null);
    }

    @Override
    public Concept getOrCreateConcept(Concept parent, String conceptIRI, String displayName, File inDir, boolean deleteChangeableProperties, User user, String workspaceId) {
        Concept concept = getConceptByIRI(conceptIRI, user, workspaceId);
        if (concept != null) {
            if (deleteChangeableProperties) {
                deleteChangeableProperties(((VertexiumConcept) concept).getVertex(), getAuthorizations(user, workspaceId));
            }
            return concept;
        }

        try (GraphUpdateContext ctx = graphRepository.beginGraphUpdate(user == null ? Priority.LOW : Priority.NORMAL, user, getAuthorizations(user, workspaceId))) {
            ctx.setPushOnQueue(false);

            Visibility visibility = VISIBILITY.getVisibility();
            String id = ID_PREFIX_CONCEPT + conceptIRI;
            if (workspaceId == null && user != null) {
                if (privilegeRepository == null) {
                    privilegeRepository = InjectHelper.getInstance(PrivilegeRepository.class);
                }
                if (!privilegeRepository.hasPrivilege(user, Privilege.ONTOLOGY_ADD)) {
                    throw new VisalloAccessDeniedException("User missing privilege to published concepts", user, null);
                }
            }

            if (workspaceId != null) {
                // TODO: Make sure user has access to workspace
                VisibilityJson visibilityJson = new VisibilityJson();
                visibilityJson.setSource(visibility.getVisibilityString());
                visibilityJson.addWorkspace(workspaceId);
                visibility = visibilityTranslator.toVisibility(visibilityJson).getVisibility();
                id = ID_PREFIX_CONCEPT + Hashing.sha1().hashString(workspaceId + conceptIRI, Charsets.UTF_8).toString();
            }

            VertexBuilder builder = graph.prepareVertex(id, visibility);
            Vertex vertex = ctx.update(builder, elemCtx -> {
                VisalloProperties.CONCEPT_TYPE.updateProperty(elemCtx, TYPE_CONCEPT, VISIBILITY.getVisibility());
                OntologyProperties.ONTOLOGY_TITLE.updateProperty(elemCtx, conceptIRI, VISIBILITY.getVisibility());
                OntologyProperties.DISPLAY_NAME.updateProperty(elemCtx, displayName, VISIBILITY.getVisibility());
                if (conceptIRI.equals(OntologyRepository.ENTITY_CONCEPT_IRI)) {
                    OntologyProperties.TITLE_FORMULA.updateProperty(elemCtx, "prop('http://visallo.org#title') || ''", VISIBILITY.getVisibility());
                    OntologyProperties.SUBTITLE_FORMULA.updateProperty(elemCtx, "prop('http://visallo.org#source') || ''", VISIBILITY.getVisibility());
                    OntologyProperties.TIME_FORMULA.updateProperty(elemCtx, "''", VISIBILITY.getVisibility());
                }
            }).get();

            if (parent == null) {
                concept = createConcept(vertex);
            } else {
                concept = createConcept(vertex, null, parent.getIRI());
                findOrAddEdge(ctx, ((VertexiumConcept) concept).getVertex(), ((VertexiumConcept) parent).getVertex(), LabelName.IS_A.toString(), user, workspaceId);
            }

            if (workspaceId != null) {
                findOrAddEdge(ctx, workspaceId, ((VertexiumConcept) concept).getVertex().getId(), WorkspaceProperties.WORKSPACE_TO_ONTOLOGY_RELATIONSHIP_IRI, user, workspaceId);
            }

            return concept;
        } catch (Exception e) {
            throw new VisalloException("Could not create concept: " + conceptIRI, e);
        }
    }

    protected void findOrAddEdge(Vertex fromVertex, final Vertex toVertex, String edgeLabel, User user, String workspaceId) {
        try (GraphUpdateContext ctx = graphRepository.beginGraphUpdate(Priority.LOW, null, getAuthorizations(user, workspaceId))) {
            ctx.setPushOnQueue(false);
            findOrAddEdge(ctx, fromVertex, toVertex, edgeLabel, user, workspaceId);
        } catch (Exception e) {
            throw new VisalloException("Could not findOrAddEdge", e);
        }
    }


    protected void findOrAddEdge(GraphUpdateContext ctx, String fromVertexId, final String toVertexId, String edgeLabel, User user, String workspaceId) {
        String edgeId = fromVertexId + "-" + toVertexId;
        ctx.getOrCreateEdgeAndUpdate(edgeId, fromVertexId, toVertexId, edgeLabel, VISIBILITY.getVisibility(), elemCtx -> {
        });
    }

    protected void findOrAddEdge(GraphUpdateContext ctx, Vertex fromVertex, final Vertex toVertex, String edgeLabel, User user, String workspaceId) {
        String edgeId = fromVertex.getId() + "-" + toVertex.getId();
        ctx.getOrCreateEdgeAndUpdate(edgeId, fromVertex.getId(), toVertex.getId(), edgeLabel, VISIBILITY.getVisibility(), elemCtx -> {
        });
    }

    @Override
    public OntologyProperty addPropertyTo(
            List<Concept> concepts,
            List<Relationship> relationships,
            String propertyIri,
            String displayName,
            PropertyType dataType,
            Map<String, String> possibleValues,
            Collection<TextIndexHint> textIndexHints,
            boolean userVisible,
            boolean searchable,
            boolean addable,
            boolean sortable,
            String displayType,
            String propertyGroup,
            Double boost,
            String validationFormula,
            String displayFormula,
            ImmutableList<String> dependentPropertyIris,
            String[] intents,
            boolean deleteable,
            boolean updateable,
            User user,
            String workspaceId
    ) {
        checkNotNull(concepts, "vertex was null");
        Vertex vertex = getOrCreatePropertyVertex(
                propertyIri,
                dataType,
                textIndexHints,
                sortable,
                boost,
                possibleValues,
                concepts,
                relationships,
                user,
                workspaceId
        );
        checkNotNull(vertex, "Could not find property: " + propertyIri);
        String vertexId = vertex.getId();

        boolean finalSearchable = determineSearchable(propertyIri, dataType, textIndexHints, searchable);

        try (GraphUpdateContext ctx = graphRepository.beginGraphUpdate(Priority.LOW, null, getAuthorizations(user, workspaceId))) {
            ctx.setPushOnQueue(false);

            ExistingElementMutation<Vertex> builder = vertex.prepareMutation();
            vertex = ctx.update(builder, elemCtx -> {
                Visibility visibility = VISIBILITY.getVisibility();
                OntologyProperties.SEARCHABLE.updateProperty(elemCtx, finalSearchable, visibility);
                OntologyProperties.SORTABLE.updateProperty(elemCtx, sortable, visibility);
                OntologyProperties.ADDABLE.updateProperty(elemCtx, addable, visibility);
                OntologyProperties.DELETEABLE.updateProperty(elemCtx, deleteable, visibility);
                OntologyProperties.UPDATEABLE.updateProperty(elemCtx, updateable, visibility);
                OntologyProperties.USER_VISIBLE.updateProperty(elemCtx, userVisible, visibility);
                if (boost != null) {
                    OntologyProperties.BOOST.updateProperty(elemCtx, boost, visibility);
                }
                if (displayName != null && !displayName.trim().isEmpty()) {
                    OntologyProperties.DISPLAY_NAME.updateProperty(elemCtx, displayName.trim(), visibility);
                }
                if (displayType != null && !displayType.trim().isEmpty()) {
                    OntologyProperties.DISPLAY_TYPE.updateProperty(elemCtx, displayType, visibility);
                }
                if (propertyGroup != null && !propertyGroup.trim().isEmpty()) {
                    OntologyProperties.PROPERTY_GROUP.updateProperty(elemCtx, propertyGroup, visibility);
                }
                if (validationFormula != null && !validationFormula.trim().isEmpty()) {
                    OntologyProperties.VALIDATION_FORMULA.updateProperty(elemCtx, validationFormula, visibility);
                }
                if (displayFormula != null && !displayFormula.trim().isEmpty()) {
                    OntologyProperties.DISPLAY_FORMULA.updateProperty(elemCtx, displayFormula, visibility);
                }
                if (dependentPropertyIris != null) {
                    saveDependentProperties(vertexId, dependentPropertyIris, user, workspaceId);
                }
                if (intents != null) {
                    Metadata metadata = new Metadata();
                    for (String intent : intents) {
                        OntologyProperties.INTENT.updateProperty(elemCtx, intent, intent, metadata, visibility);
                    }
                }
            }).get();

            return createOntologyProperty(vertex, dependentPropertyIris);
        } catch (Exception e) {
            throw new VisalloException("Could not create property: " + propertyIri, e);
        }
    }

    @Override
    protected void getOrCreateInverseOfRelationship(Relationship fromRelationship, Relationship inverseOfRelationship) {
        checkNotNull(fromRelationship, "fromRelationship is required");
        checkNotNull(fromRelationship, "inverseOfRelationship is required");

        VertexiumRelationship fromRelationshipSg = (VertexiumRelationship) fromRelationship;
        VertexiumRelationship inverseOfRelationshipSg = (VertexiumRelationship) inverseOfRelationship;

        Vertex fromVertex = fromRelationshipSg.getVertex();
        checkNotNull(fromVertex, "fromVertex is required");

        Vertex inverseVertex = inverseOfRelationshipSg.getVertex();
        checkNotNull(inverseVertex, "inverseVertex is required");

        findOrAddEdge(fromVertex, inverseVertex, LabelName.INVERSE_OF.toString(), null, null);
        findOrAddEdge(inverseVertex, fromVertex, LabelName.INVERSE_OF.toString(), null, null);
    }

    @Override
    public Relationship getOrCreateRelationshipType(
            Relationship parent,
            Iterable<Concept> domainConcepts,
            Iterable<Concept> rangeConcepts,
            String relationshipIRI,
            boolean isDeclaredInOntology,
            User user,
            String workspaceId
    ) {
        Relationship relationship = getRelationshipByIRI(relationshipIRI, user, workspaceId);
        if (relationship != null) {
            if (isDeclaredInOntology) {
                deleteChangeableProperties(((VertexiumRelationship) relationship).getVertex(), getAuthorizations(user, workspaceId));
            }
            return relationship;
        }

        try (GraphUpdateContext ctx = graphRepository.beginGraphUpdate(Priority.LOW, null, getAuthorizations(user, workspaceId))) {
            ctx.setPushOnQueue(false);

            VertexBuilder builder = graph.prepareVertex(ID_PREFIX_RELATIONSHIP + relationshipIRI, VISIBILITY.getVisibility());
            Vertex relationshipVertex = ctx.update(builder, elemCtx -> {
                VisalloProperties.CONCEPT_TYPE.updateProperty(elemCtx, TYPE_RELATIONSHIP, VISIBILITY.getVisibility());
                OntologyProperties.ONTOLOGY_TITLE.updateProperty(elemCtx, relationshipIRI, VISIBILITY.getVisibility());
            }).get();

            for (Concept domainConcept : domainConcepts) {
                findOrAddEdge(ctx, ((VertexiumConcept) domainConcept).getVertex(), relationshipVertex, LabelName.HAS_EDGE.toString(), user, workspaceId);
            }

            for (Concept rangeConcept : rangeConcepts) {
                findOrAddEdge(ctx, relationshipVertex, ((VertexiumConcept) rangeConcept).getVertex(), LabelName.HAS_EDGE.toString(), user, workspaceId);
            }

            if (parent != null) {
                findOrAddEdge(ctx, relationshipVertex, ((VertexiumRelationship) parent).getVertex(), LabelName.IS_A.toString(), user, workspaceId);
            }

            List<String> inverseOfIRIs = new ArrayList<>(); // no inverse of because this relationship is new

            List<String> domainConceptIris = Lists.newArrayList(new ConvertingIterable<Concept, String>(domainConcepts) {
                @Override
                protected String convert(Concept o) {
                    return o.getIRI();
                }
            });

            List<String> rangeConceptIris = Lists.newArrayList(new ConvertingIterable<Concept, String>(rangeConcepts) {
                @Override
                protected String convert(Concept o) {
                    return o.getIRI();
                }
            });

            Collection<OntologyProperty> properties = new ArrayList<>();
            String parentIRI = parent == null ? null : parent.getIRI();
            return createRelationship(parentIRI, relationshipVertex, inverseOfIRIs, domainConceptIris, rangeConceptIris, properties);
        } catch (Exception ex) {
            throw new VisalloException("Could not create relationship: " + relationshipIRI, ex);
        }
    }

    private Vertex getOrCreatePropertyVertex(
            final String propertyIri,
            final PropertyType dataType,
            Collection<TextIndexHint> textIndexHints,
            boolean sortable,
            Double boost,
            Map<String, String> possibleValues,
            List<Concept> concepts,
            List<Relationship> relationships,
            User user,
            String workspaceId
    ) {
        OntologyProperty typeProperty = getPropertyByIRI(propertyIri, user, workspaceId);
        Vertex propertyVertex;
        if (typeProperty == null) {
            definePropertyOnGraph(graph, propertyIri, dataType, textIndexHints, boost, sortable);

            String propertyVertexId = ID_PREFIX_PROPERTY + propertyIri;
            try (GraphUpdateContext ctx = graphRepository.beginGraphUpdate(Priority.LOW, null, getAuthorizations(user, workspaceId))) {
                ctx.setPushOnQueue(false);

                VertexBuilder builder = graph.prepareVertex(propertyVertexId, VISIBILITY.getVisibility());
                propertyVertex = ctx.update(builder, elemCtx -> {
                    VisalloProperties.CONCEPT_TYPE.updateProperty(elemCtx, TYPE_PROPERTY, VISIBILITY.getVisibility());
                    OntologyProperties.ONTOLOGY_TITLE.updateProperty(elemCtx, propertyIri, VISIBILITY.getVisibility());
                    OntologyProperties.DATA_TYPE.updateProperty(elemCtx, dataType.toString(), VISIBILITY.getVisibility());
                    if (possibleValues != null) {
                        OntologyProperties.POSSIBLE_VALUES.updateProperty(elemCtx, JSONUtil.toJson(possibleValues), VISIBILITY.getVisibility());
                    }
                    if (textIndexHints.size() > 0) {
                        Metadata metadata = new Metadata();
                        textIndexHints.forEach(i -> {
                            String textIndexHint = i.toString();
                            OntologyProperties.TEXT_INDEX_HINTS.updateProperty(elemCtx, textIndexHint, textIndexHint, metadata, VISIBILITY.getVisibility());
                        });
                    }
                }).get();

                for (Concept concept : concepts) {
                    checkNotNull(concept, "concepts cannot have null values");
                    findOrAddEdge(ctx, ((VertexiumConcept) concept).getVertex(), propertyVertex, LabelName.HAS_PROPERTY.toString(), user, workspaceId);
                }
                for (Relationship relationship : relationships) {
                    checkNotNull(relationships, "relationships cannot have null values");
                    findOrAddEdge(ctx, ((VertexiumRelationship) relationship).getVertex(), propertyVertex, LabelName.HAS_PROPERTY.toString(), user, workspaceId);
                }
            } catch (Exception e) {
                throw new VisalloException("Could not getOrCreatePropertyVertex: " + propertyIri, e);
            }
        } else {
            propertyVertex = ((VertexiumOntologyProperty) typeProperty).getVertex();
            deleteChangeableProperties(propertyVertex, authorizations);
        }
        return propertyVertex;
    }

    private void saveDependentProperties(String propertyVertexId, Collection<String> dependentPropertyIris, User user, String workspaceId) {
        int i;
        for (i = 0; i < 1000; i++) {
            String edgeId = propertyVertexId + "-dependentProperty-" + i;
            Edge edge = graph.getEdge(edgeId, authorizations);
            if (edge == null) {
                break;
            }
            graph.deleteEdge(edge, authorizations);
        }
        graph.flush();

        i = 0;
        for (String dependentPropertyIri : dependentPropertyIris) {
            String dependentPropertyVertexId = ID_PREFIX_PROPERTY + dependentPropertyIri;
            String edgeId = propertyVertexId + "-dependentProperty-" + i;
            EdgeBuilderByVertexId m = graph.prepareEdge(edgeId, propertyVertexId, dependentPropertyVertexId, OntologyProperties.EDGE_LABEL_DEPENDENT_PROPERTY, VISIBILITY.getVisibility());
            OntologyProperties.DEPENDENT_PROPERTY_ORDER_PROPERTY_NAME.setProperty(m, i, VISIBILITY.getVisibility());
            m.save(authorizations);
            i++;
        }
    }

    @Override
    public void updatePropertyDependentIris(OntologyProperty property, Collection<String> newDependentPropertyIris, User user, String workspaceId) {
        VertexiumOntologyProperty vertexiumProperty = (VertexiumOntologyProperty) property;
        saveDependentProperties(vertexiumProperty.getVertex().getId(), newDependentPropertyIris, user, workspaceId);
        graph.flush();
        vertexiumProperty.setDependentProperties(newDependentPropertyIris);
    }

    @Override
    public void updatePropertyDomainIris(OntologyProperty property, Set<String> domainIris, User user, String workspaceId) {
        VertexiumOntologyProperty vertexiumProperty = (VertexiumOntologyProperty) property;

        Iterable<EdgeVertexPair> existingConcepts = vertexiumProperty.getVertex().getEdgeVertexPairs(Direction.BOTH, LabelName.HAS_PROPERTY.toString(), getAuthorizations(user, workspaceId));
        for (EdgeVertexPair existingConcept : existingConcepts) {
            String conceptIri = OntologyProperties.ONTOLOGY_TITLE.getPropertyValue(existingConcept.getVertex());
            if (!domainIris.remove(conceptIri)) {
                getGraph().softDeleteEdge(existingConcept.getEdge(), getAuthorizations(user, workspaceId));
            }
        }

        for (String domainIri : domainIris) {
            Vertex domainVertex;
            Concept concept = getConceptByIRI(domainIri);
            if (concept != null) {
                domainVertex = ((VertexiumConcept) concept).getVertex();
            } else {
                Relationship relationship = getRelationshipByIRI(domainIri);
                if (relationship != null) {
                    domainVertex = ((VertexiumRelationship) relationship).getVertex();
                } else {
                    throw new VisalloException("Could not find domain with IRI " + domainIri);
                }
            }
            findOrAddEdge(domainVertex, ((VertexiumOntologyProperty) property).getVertex(), LabelName.HAS_PROPERTY.toString(), user, workspaceId);
        }
    }

    private Vertex getParentConceptVertex(Vertex conceptVertex, User user, String workspaceId) {
        try {
            return Iterables.getOnlyElement(conceptVertex.getVertices(Direction.OUT, LabelName.IS_A.toString(), getAuthorizations(user, workspaceId)), null);
        } catch (IllegalArgumentException iae) {
            throw new IllegalStateException(String.format(
                    "Unexpected number of parents for concept %s",
                    OntologyProperties.TITLE.getPropertyValue(conceptVertex)
            ), iae);
        }
    }

    protected Authorizations getAuthorizations(User user, String workspaceId) {
        if (user == null) {
            if (workspaceId == null) {
                return authorizations;
            }
            return graph.createAuthorizations(authorizations, workspaceId);
        }
        if (workspaceId == null) {
            if (privilegeRepository.hasPrivilege(user, Privilege.ONTOLOGY_ADD)) {
                return authorizationRepository.getGraphAuthorizations(user, VISIBILITY_STRING);
            }
            throw new VisalloAccessDeniedException("User is missing ONTOLOGY_ADD privilege", user, null);
        }

        return authorizationRepository.getGraphAuthorizations(user, workspaceId, VISIBILITY_STRING);
    }

    protected Graph getGraph() {
        return graph;
    }

    /**
     * Overridable so subclasses can supply a custom implementation of OntologyProperty.
     */
    protected OntologyProperty createOntologyProperty(
            Vertex propertyVertex,
            ImmutableList<String> dependentPropertyIris
    ) {
        return new VertexiumOntologyProperty(propertyVertex, dependentPropertyIris);
    }

    /**
     * Overridable so subclasses can supply a custom implementation of Relationship.
     */
    protected Relationship createRelationship(
            String parentIRI,
            Vertex relationshipVertex,
            List<String> inverseOfIRIs,
            List<String> domainConceptIris,
            List<String> rangeConceptIris,
            Collection<OntologyProperty> properties
    ) {
        return new VertexiumRelationship(
                parentIRI,
                relationshipVertex,
                domainConceptIris,
                rangeConceptIris,
                inverseOfIRIs,
                properties
        );
    }

    /**
     * Overridable so subclasses can supply a custom implementation of Concept.
     */
    protected Concept createConcept(Vertex vertex, List<OntologyProperty> conceptProperties, String parentConceptIRI) {
        return new VertexiumConcept(vertex, parentConceptIRI, conceptProperties);
    }

    /**
     * Overridable so subclasses can supply a custom implementation of Concept.
     */
    protected Concept createConcept(Vertex vertex) {
        return new VertexiumConcept(vertex);
    }

    private void deleteChangeableProperties(Vertex vertex, Authorizations authorizations) {
        for (Property property : vertex.getProperties()) {
            if (OntologyProperties.CHANGEABLE_PROPERTY_IRI.contains(property.getName())) {
                vertex.softDeleteProperty(property.getKey(), property.getName(), authorizations);
            }
        }
        graph.flush();
    }

    private List<OntologyProperty> transformProperties(Iterable<Vertex> vertices, User user, String workspaceId) {
        return Lists.newArrayList(Iterables.transform(vertices, new Function<Vertex, OntologyProperty>() {
            @Nullable
            @Override
            public OntologyProperty apply(@Nullable Vertex vertex) {
                return createOntologyProperty(vertex, getDependentPropertyIris(vertex, user, workspaceId));
            }
        }));
    }

    private List<Concept> transformConcepts(Iterable<Vertex> vertices, User user, String workspaceId) {
        return Lists.newArrayList(Iterables.transform(vertices, new Function<Vertex, Concept>() {
            @Nullable
            @Override
            public Concept apply(@Nullable Vertex vertex) {
                List<OntologyProperty> conceptProperties = getPropertiesByVertexNoRecursion(vertex, user, workspaceId);
                Vertex parentConceptVertex = getParentConceptVertex(vertex, user, workspaceId);
                String parentConceptIRI = OntologyProperties.ONTOLOGY_TITLE.getPropertyValue(parentConceptVertex);
                return createConcept(vertex, conceptProperties, parentConceptIRI);
            }
        }));
    }

    private List<Relationship> transformRelationships(Iterable<Vertex> vertices, User user, String workspaceId) {
        return Lists.newArrayList(Iterables.transform(vertices, new Function<Vertex, Relationship>() {
            @Nullable
            @Override
            public Relationship apply(@Nullable Vertex vertex) {
                return toVertexiumRelationship(vertex, user, workspaceId);
            }
        }));
    }

    private String cacheKey(String workspaceId) {
        return (workspaceId == null ? "" : workspaceId);
    }
}
