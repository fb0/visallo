package org.visallo.vertexium.model.ontology;

import com.google.common.base.Charsets;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.hash.Hashing;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.semanticweb.owlapi.io.OWLOntologyDocumentSource;
import org.semanticweb.owlapi.io.ReaderDocumentSource;
import org.semanticweb.owlapi.model.*;
import org.vertexium.*;
import org.vertexium.mutation.ExistingElementMutation;
import org.vertexium.property.StreamingPropertyValue;
import org.vertexium.util.CloseableUtils;
import org.vertexium.util.ConvertingIterable;
import org.visallo.core.bootstrap.InjectHelper;
import org.visallo.core.config.Configuration;
import org.visallo.core.exception.VisalloException;
import org.visallo.core.model.graph.GraphRepository;
import org.visallo.core.model.graph.GraphUpdateContext;
import org.visallo.core.model.lock.LockRepository;
import org.visallo.core.model.ontology.*;
import org.visallo.core.model.properties.VisalloProperties;
import org.visallo.core.model.user.AuthorizationRepository;
import org.visallo.core.model.user.GraphAuthorizationRepository;
import org.visallo.core.model.workQueue.Priority;
import org.visallo.core.model.workspace.WorkspaceProperties;
import org.visallo.core.security.VisalloVisibility;
import org.visallo.core.security.VisibilityTranslator;
import org.visallo.core.user.User;
import org.visallo.core.util.JSONUtil;
import org.visallo.core.util.TimingCallable;
import org.visallo.core.util.VisalloLogger;
import org.visallo.core.util.VisalloLoggerFactory;
import org.visallo.web.clientapi.model.ClientApiOntology;
import org.visallo.web.clientapi.model.PropertyType;
import org.visallo.web.clientapi.model.SandboxStatus;
import org.visallo.web.clientapi.model.VisibilityJson;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

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

    private Authorizations publicOntologyAuthorizations;

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

            publicOntologyAuthorizations = graph.createAuthorizations(Collections.singleton(VISIBILITY_STRING));

            loadOntologies(config, publicOntologyAuthorizations);
        } catch (Exception ex) {
            LOGGER.error("Could not initialize: %s", this.getClass().getName(), ex);
            throw ex;
        }
    }

    protected AuthorizationRepository getAuthorizationRepository() {
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

        if (!graph.isPropertyDefined(OntologyProperties.MAP_GLYPH_ICON.getPropertyName())) {
            graph.defineProperty(OntologyProperties.MAP_GLYPH_ICON.getPropertyName())
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
    public ClientApiOntology getClientApiObject(String workspaceId) {
        String key = cacheKey(workspaceId);
        ClientApiOntology o = this.clientApiCache.getIfPresent(key);
        if (o != null) {
            return o;
        }

        try {
            return this.clientApiCache.get(cacheKey(workspaceId), new TimingCallable<ClientApiOntology>("getClientApiObject") {
                @Override
                protected ClientApiOntology callWithTime() throws Exception {
                    return VertexiumOntologyRepository.super.getClientApiObject(workspaceId);
                }
            });
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
    protected void addEntityGlyphIconToEntityConcept(Concept entityConcept, byte[] rawImg, Authorizations authorizations) {
        StreamingPropertyValue raw = new StreamingPropertyValue(new ByteArrayInputStream(rawImg), byte[].class);
        raw.searchIndex(false);
        entityConcept.setProperty(OntologyProperties.GLYPH_ICON.getPropertyName(), raw, authorizations);
        graph.flush();
    }

    @Override
    public void storeOntologyFile(InputStream in, IRI documentIRI, Authorizations authorizations) {
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
        Vertex rootConceptVertex = ((VertexiumConcept) getRootConcept(null)).getVertex();
        metadata.add("index", Iterables.size(OntologyProperties.ONTOLOGY_FILE.getProperties(rootConceptVertex)), VISIBILITY.getVisibility());
        OntologyProperties.ONTOLOGY_FILE.addPropertyValue(rootConceptVertex, documentIRI.toString(), value, metadata, VISIBILITY.getVisibility(), authorizations);
        OntologyProperties.ONTOLOGY_FILE_MD5.addPropertyValue(rootConceptVertex, documentIRI.toString(), md5, metadata, VISIBILITY.getVisibility(), authorizations);
        graph.flush();
    }

    @Override
    protected boolean hasFileChanged(IRI documentIRI, byte[] inFileData) {
        Vertex rootConceptVertex = ((VertexiumConcept) getRootConcept(null)).getVertex();
        String existingMd5 = OntologyProperties.ONTOLOGY_FILE_MD5.getPropertyValue(rootConceptVertex, documentIRI.toString());
        return existingMd5 == null || !DigestUtils.md5Hex(inFileData).equals(existingMd5);
    }

    @Deprecated
    @Override
    public boolean isOntologyDefined(String iri) {
        Vertex rootConceptVertex = ((VertexiumConcept) getRootConcept(null)).getVertex();
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
                    LOGGER.warn("Could not load existing %s", ontologyFileIRI, ex);
                }
            }
        }
        return loadedOntologies;
    }

    private Iterable<Property> getOntologyFiles() {
        VertexiumConcept rootConcept = (VertexiumConcept) getRootConcept(null);
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
    public Iterable<Relationship> getRelationships(Iterable<String> ids, String workspaceId) {
        return transformRelationships(graph.getVertices(ids, getAuthorizations(workspaceId)), workspaceId);
    }

    @Override
    public Iterable<Relationship> getRelationships(String workspaceId) {
        try {
            return relationshipLabelsCache.get(cacheKey(workspaceId), new TimingCallable<List<Relationship>>("getRelationships") {
                @Override
                public List<Relationship> callWithTime() throws Exception {
                    Iterable<Vertex> vertices = graph.getVerticesWithPrefix(ID_PREFIX_RELATIONSHIP, getAuthorizations(workspaceId));
                    return transformRelationships(vertices, workspaceId);
                }
            });
        } catch (ExecutionException e) {
            throw new VisalloException("Could not get relationship labels");
        }
    }

    private Relationship toVertexiumRelationship(String parentIRI, Vertex relationshipVertex, String workspaceId) {
        Authorizations authorizations = getAuthorizations(workspaceId);

        Iterable<EdgeInfo> domainEdgeInfos = relationshipVertex.getEdgeInfos(Direction.IN, LabelName.HAS_EDGE.toString(), authorizations);
        Set<String> domainVertexIds = StreamSupport.stream(domainEdgeInfos.spliterator(), false).map(EdgeInfo::getVertexId).collect(Collectors.toSet());

        Iterable<EdgeInfo> rangeEdgeInfos = relationshipVertex.getEdgeInfos(Direction.OUT, LabelName.HAS_EDGE.toString(), authorizations);
        Set<String> rangeVertexIds = StreamSupport.stream(rangeEdgeInfos.spliterator(), false).map(EdgeInfo::getVertexId).collect(Collectors.toSet());

        Iterable<Vertex> domainAndRangeVertices = graph.getVertices(Iterables.concat(domainVertexIds, rangeVertexIds), EnumSet.of(FetchHint.PROPERTIES), authorizations);
        List<String> domainIris = new ArrayList<>();
        List<String> rangeIris = new ArrayList<>();
        domainAndRangeVertices.forEach(vertex -> {
            String iri = OntologyProperties.ONTOLOGY_TITLE.getPropertyValue(vertex);
            if (domainVertexIds.contains(vertex.getId())) {
                domainIris.add(iri);
            }
            if (rangeVertexIds.contains(vertex.getId())) {
                rangeIris.add(iri);
            }
        });

        final List<String> inverseOfIRIs = getRelationshipInverseOfIRIs(relationshipVertex, workspaceId);
        List<OntologyProperty> properties = getPropertiesByVertexNoRecursion(relationshipVertex, workspaceId);
        return createRelationship(parentIRI, relationshipVertex, inverseOfIRIs, domainIris, rangeIris, properties, workspaceId);
    }

    private List<String> getRelationshipInverseOfIRIs(final Vertex vertex, String workspaceId) {
        return Lists.newArrayList(new ConvertingIterable<Vertex, String>(vertex.getVertices(Direction.OUT, LabelName.INVERSE_OF.toString(), getAuthorizations(workspaceId))) {
            @Override
            protected String convert(Vertex inverseOfVertex) {
                return OntologyProperties.ONTOLOGY_TITLE.getPropertyValue(inverseOfVertex);
            }
        });
    }

    @Override
    public String getDisplayNameForLabel(String relationshipIRI, String workspaceId) {
        String displayName = null;
        if (relationshipIRI != null && !relationshipIRI.trim().isEmpty()) {
            try {
                Relationship relationship = getRelationshipByIRI(relationshipIRI, workspaceId);
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
    public Iterable<OntologyProperty> getProperties(Iterable<String> ids, String workspaceId) {
        return transformProperties(graph.getVertices(ids, getAuthorizations(workspaceId)), workspaceId);
    }

    @Override
    public Iterable<OntologyProperty> getProperties(String workspaceId) {
        try {
            return allPropertiesCache.get(cacheKey(workspaceId), new TimingCallable<List<OntologyProperty>>("getProperties") {
                @Override
                public List<OntologyProperty> callWithTime() throws Exception {
                    Iterable<Vertex> vertices = graph.getVerticesWithPrefix(ID_PREFIX_PROPERTY, getAuthorizations(workspaceId));
                    return transformProperties(vertices, workspaceId);
                }
            });
        } catch (ExecutionException e) {
            throw new VisalloException("Could not get properties", e);
        }
    }

    protected ImmutableList<String> getDependentPropertyIris(final Vertex vertex, String workspaceId) {
        List<Edge> dependentProperties = Lists.newArrayList(vertex.getEdges(Direction.OUT, OntologyProperties.EDGE_LABEL_DEPENDENT_PROPERTY, getAuthorizations(workspaceId)));
        dependentProperties.sort((e1, e2) -> {
            Integer o1 = OntologyProperties.DEPENDENT_PROPERTY_ORDER_PROPERTY_NAME.getPropertyValue(e1, 0);
            Integer o2 = OntologyProperties.DEPENDENT_PROPERTY_ORDER_PROPERTY_NAME.getPropertyValue(e2, 0);
            return Integer.compare(o1, o2);
        });
        return ImmutableList.copyOf(dependentProperties.stream().map(e -> {
            String propertyId = e.getOtherVertexId(vertex.getId());
            return propertyId.substring(VertexiumOntologyRepository.ID_PREFIX_PROPERTY.length());
        }).collect(Collectors.toList()));
    }

    @Override
    public Iterable<Concept> getConceptsWithProperties(String workspaceId) {
        try {
            return allConceptsWithPropertiesCache.get(cacheKey(workspaceId), new TimingCallable<List<Concept>>("getConceptsWithProperties") {
                @Override
                public List<Concept> callWithTime() throws Exception {
                    Authorizations authorizations = getAuthorizations(workspaceId);
                    Iterable<Vertex> vertices = graph.getVerticesWithPrefix(ID_PREFIX_CONCEPT, authorizations);
                    return transformConcepts(vertices, workspaceId);
                }
            });
        } catch (ExecutionException e) {
            throw new VisalloException("could not get concepts with properties", e);
        }
    }

    @Override
    public Concept getRootConcept(String workspaceId) {
        return getConceptByIRI(VertexiumOntologyRepository.ROOT_CONCEPT_IRI, workspaceId);
    }

    @Override
    public Concept getEntityConcept(String workspaceId) {
        return getConceptByIRI(VertexiumOntologyRepository.ENTITY_CONCEPT_IRI, workspaceId);
    }

    @Override
    protected List<Concept> getChildConcepts(Concept concept, String workspaceId) {
        Vertex conceptVertex = ((VertexiumConcept) concept).getVertex();
        return toConcepts(conceptVertex.getVertices(Direction.IN, LabelName.IS_A.toString(), getAuthorizations(workspaceId)), workspaceId);
    }

    @Override
    protected List<Relationship> getChildRelationships(Relationship relationship, String workspaceId) {
        Vertex relationshipVertex = ((VertexiumRelationship) relationship).getVertex();
        return toRelationships(relationshipVertex.getVertices(Direction.IN, LabelName.IS_A.toString(), getAuthorizations(workspaceId)), workspaceId);
    }

    @Override
    public Relationship getParentRelationship(Relationship relationship, String workspaceId) {
        Vertex parentVertex = getParentVertex(((VertexiumRelationship) relationship).getVertex(), workspaceId);
        if (parentVertex == null) {
            return null;
        }

        String parentIri = OntologyProperties.ONTOLOGY_TITLE.getPropertyValue(parentVertex);
        return getRelationshipByIRI(parentIri, workspaceId);
    }

    @Override
    public Concept getParentConcept(final Concept concept, String workspaceId) {
        Vertex parentConceptVertex = getParentVertex(((VertexiumConcept) concept).getVertex(), workspaceId);
        if (parentConceptVertex == null) {
            return null;
        }

        String parentIri = OntologyProperties.ONTOLOGY_TITLE.getPropertyValue(parentConceptVertex);
        return getConceptByIRI(parentIri, workspaceId);
    }

    private List<Concept> toConcepts(Iterable<Vertex> vertices, String workspaceId) {
        ArrayList<Concept> concepts = new ArrayList<>();
        for (Vertex vertex : vertices) {
            concepts.add(createConcept(vertex, workspaceId));
        }
        return concepts;
    }

    private List<Relationship> toRelationships(Iterable<Vertex> vertices, String workspaceId) {
        ArrayList<Relationship> relationships = new ArrayList<>();

        Authorizations authorizations = getAuthorizations(workspaceId);
        Map<String, String> parentVertexIdToIRI = buildParentIdToIriMap(vertices, authorizations);

        for (Vertex vertex : vertices) {
            String parentVertexId = getParentVertexId(vertex, authorizations);
            String parentIRI = parentVertexId == null ? null : parentVertexIdToIRI.get(parentVertexId);
            relationships.add(toVertexiumRelationship(parentIRI, vertex, workspaceId));
        }
        return relationships;
    }

    private List<OntologyProperty> getPropertiesByVertexNoRecursion(Vertex vertex, String workspaceId) {
        return Lists.newArrayList(new ConvertingIterable<Vertex, OntologyProperty>(vertex.getVertices(Direction.OUT, LabelName.HAS_PROPERTY.toString(), getAuthorizations(workspaceId))) {
            @Override
            protected OntologyProperty convert(Vertex o) {
                return createOntologyProperty(o, getDependentPropertyIris(o, workspaceId), VertexiumOntologyProperty.getDataType(o), workspaceId);
            }
        });
    }

    @Override
    public Iterable<Concept> getConcepts(Iterable<String> ids, String workspaceId) {
        return transformConcepts(graph.getVertices(ids, getAuthorizations(workspaceId)), workspaceId);
    }

    @Override
    protected Concept internalGetOrCreateConcept(Concept parent, String conceptIRI, String displayName, String glyphIconHref, String color, File inDir, boolean deleteChangeableProperties, User user, String workspaceId) {
        Concept concept = getConceptByIRI(conceptIRI, workspaceId);
        if (concept != null) {
            if (deleteChangeableProperties) {
                deleteChangeableProperties(concept, getAuthorizations(workspaceId));
            }
            return concept;
        }

        try (GraphUpdateContext ctx = graphRepository.beginGraphUpdate(getPriority(user), user, getAuthorizations(workspaceId))) {
            ctx.setPushOnQueue(false);

            Visibility visibility = VISIBILITY.getVisibility();
            VisibilityJson visibilityJson = new VisibilityJson();
            visibilityJson.setSource(visibility.getVisibilityString());

            VertexBuilder builder = prepareVertex(ID_PREFIX_CONCEPT, conceptIRI, workspaceId, visibility, visibilityJson);

            Date modifiedDate = new Date();
            Vertex vertex = ctx.update(builder, modifiedDate, visibilityJson, TYPE_CONCEPT, elemCtx -> {
                Metadata metadata = getMetadata(modifiedDate, user, visibility);
                OntologyProperties.ONTOLOGY_TITLE.updateProperty(elemCtx, conceptIRI, metadata, visibility);
                OntologyProperties.DISPLAY_NAME.updateProperty(elemCtx, displayName, metadata, visibility);
                if (conceptIRI.equals(OntologyRepository.ENTITY_CONCEPT_IRI)) {
                    OntologyProperties.TITLE_FORMULA.updateProperty(elemCtx, "prop('http://visallo.org#title') || ('Untitled ' + ontology && ontology.displayName) ", metadata, visibility);
                    OntologyProperties.SUBTITLE_FORMULA.updateProperty(elemCtx, "(ontology && ontology.displayName) || prop('http://visallo.org#source') || ''", metadata, visibility);
                    OntologyProperties.TIME_FORMULA.updateProperty(elemCtx, "prop('http://visallo.org#modifiedDate') || ''", metadata, visibility);
                }
                if (!StringUtils.isEmpty(glyphIconHref)) {
                    OntologyProperties.GLYPH_ICON_FILE_NAME.updateProperty(elemCtx, glyphIconHref, metadata, visibility);
                }
                if (!StringUtils.isEmpty(color)) {
                    OntologyProperties.COLOR.updateProperty(elemCtx, color, metadata, visibility);
                }
            }).get();

            if (parent == null) {
                concept = createConcept(vertex, workspaceId);
            } else {
                concept = createConcept(vertex, null, parent.getIRI(), workspaceId);
                findOrAddEdge(ctx, ((VertexiumConcept) concept).getVertex(), ((VertexiumConcept) parent).getVertex(), LabelName.IS_A.toString());
            }

            if (workspaceId != null) {
                findOrAddEdge(ctx, workspaceId, ((VertexiumConcept) concept).getVertex().getId(), WorkspaceProperties.WORKSPACE_TO_ONTOLOGY_RELATIONSHIP_IRI);
            }

            return concept;
        } catch (Exception e) {
            throw new VisalloException("Could not create concept: " + conceptIRI, e);
        }
    }

    private Metadata getMetadata(Date modifiedDate, User user, Visibility visibility) {
        Metadata metadata = null;
        if (user != null) {
            metadata = new Metadata();
            VisalloProperties.MODIFIED_BY_METADATA.setMetadata(metadata, user.getUserId(), visibility);
            VisalloProperties.MODIFIED_DATE_METADATA.setMetadata(metadata, modifiedDate, visibility);
        }
        return metadata;
    }

    private VertexBuilder prepareVertex(String prefix, String iri, String workspaceId, Visibility visibility, VisibilityJson visibilityJson) {

        if (workspaceId == null) {
            return graph.prepareVertex(prefix + iri, visibility);
        }

        String id = prefix + Hashing.sha1().hashString(workspaceId + iri, Charsets.UTF_8).toString();

        visibilityJson.addWorkspace(workspaceId);

        return graph.prepareVertex(id, visibilityTranslator.toVisibility(visibilityJson).getVisibility());
    }

    protected void findOrAddEdge(Vertex fromVertex, final Vertex toVertex, String edgeLabel, User user, String workspaceId) {
        try (GraphUpdateContext ctx = graphRepository.beginGraphUpdate(Priority.LOW, null, getAuthorizations(workspaceId))) {
            ctx.setPushOnQueue(false);
            findOrAddEdge(ctx, fromVertex, toVertex, edgeLabel);
        } catch (Exception e) {
            throw new VisalloException("Could not findOrAddEdge", e);
        }
    }


    protected void removeEdge(GraphUpdateContext ctx, String fromVertexId, final String toVertexId) {
        String edgeId = fromVertexId + "-" + toVertexId;
        ctx.getGraph().deleteEdge(edgeId, ctx.getAuthorizations());
    }

    protected void findOrAddEdge(GraphUpdateContext ctx, String fromVertexId, final String toVertexId, String edgeLabel) {
        String edgeId = fromVertexId + "-" + toVertexId;
        ctx.getOrCreateEdgeAndUpdate(edgeId, fromVertexId, toVertexId, edgeLabel, VISIBILITY.getVisibility(), elemCtx -> {
        });
    }

    protected void findOrAddEdge(GraphUpdateContext ctx, Vertex fromVertex, final Vertex toVertex, String edgeLabel) {
        String edgeId = fromVertex.getId() + "-" + toVertex.getId();
        ctx.getOrCreateEdgeAndUpdate(edgeId, fromVertex.getId(), toVertex.getId(), edgeLabel, VISIBILITY.getVisibility(), elemCtx -> {
        });
    }

    @Override
    public void addPropertyToConcepts(OntologyProperty property, List<Concept> concepts, User user, String workspaceId) {
        try (GraphUpdateContext ctx = graphRepository.beginGraphUpdate(Priority.LOW, null, getAuthorizations(workspaceId))) {
            ctx.setPushOnQueue(false);
            Vertex propertyVertex = ((VertexiumOntologyProperty) property).getVertex();
            for (Concept concept : concepts) {
                checkNotNull(concept, "concepts cannot have null values");
                findOrAddEdge(ctx, ((VertexiumConcept) concept).getVertex(), propertyVertex, LabelName.HAS_PROPERTY.toString());
            }
        } catch (Exception e) {
            throw new VisalloException("Could not findOrAddEdge", e);
        }
    }

    @Override
    public void addPropertyToRelationships(OntologyProperty property, List<Relationship> relationships, User user, String workspaceId) {
        try (GraphUpdateContext ctx = graphRepository.beginGraphUpdate(Priority.LOW, null, getAuthorizations(workspaceId))) {
            ctx.setPushOnQueue(false);
            Vertex propertyVertex = ((VertexiumOntologyProperty) property).getVertex();
            for (Relationship relationship : relationships) {
                checkNotNull(relationships, "relationships cannot have null values");
                findOrAddEdge(ctx, ((VertexiumRelationship) relationship).getVertex(), propertyVertex, LabelName.HAS_PROPERTY.toString());
            }
        } catch (Exception e) {
            throw new VisalloException("Could not findOrAddEdge", e);
        }
    }

    @Override
    protected OntologyProperty addPropertyTo(
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
        if (CollectionUtils.isEmpty(concepts) && CollectionUtils.isEmpty(relationships)) {
            throw new VisalloException("Must specify concepts or relationships to add property");
        }
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

        try (GraphUpdateContext ctx = graphRepository.beginGraphUpdate(Priority.LOW, null, getAuthorizations(workspaceId))) {
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
                if (possibleValues != null) {
                    OntologyProperties.POSSIBLE_VALUES.updateProperty(elemCtx, JSONUtil.toJson(possibleValues), VISIBILITY.getVisibility());
                }
                if (intents != null) {
                    Metadata metadata = new Metadata();
                    for (String intent : intents) {
                        OntologyProperties.INTENT.updateProperty(elemCtx, intent, intent, metadata, visibility);
                    }
                }
            }).get();

            return createOntologyProperty(vertex, dependentPropertyIris, dataType, workspaceId);
        } catch (Exception e) {
            throw new VisalloException("Could not create property: " + propertyIri, e);
        }
    }

    @Override
    protected void addExtendedDataTableProperty(OntologyProperty tableProperty, OntologyProperty property, User user, String workspaceId) {
        if (!(tableProperty instanceof VertexiumExtendedDataTableOntologyProperty)) {
            throw new VisalloException("Invalid table property type: " + tableProperty.getDataType());
        }

        Vertex tablePropertyVertex = ((VertexiumExtendedDataTableOntologyProperty) tableProperty).getVertex();
        Vertex propertyVertex = ((VertexiumOntologyProperty) property).getVertex();

        findOrAddEdge(tablePropertyVertex, propertyVertex, LabelName.HAS_PROPERTY.toString(), user, workspaceId);
        ((VertexiumExtendedDataTableOntologyProperty) tableProperty).addProperty(property.getIri());
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
    protected Relationship internalGetOrCreateRelationshipType(
            Relationship parent,
            Iterable<Concept> domainConcepts,
            Iterable<Concept> rangeConcepts,
            String relationshipIRI,
            String displayName,
            boolean isDeclaredInOntology,
            User user,
            String workspaceId
    ) {
        Relationship relationship = getRelationshipByIRI(relationshipIRI, workspaceId);
        if (relationship != null) {
            if (isDeclaredInOntology) {
                deleteChangeableProperties(relationship, getAuthorizations(workspaceId));
            }
            return relationship;
        }

        try (GraphUpdateContext ctx = graphRepository.beginGraphUpdate(getPriority(user), user, getAuthorizations(workspaceId))) {
            ctx.setPushOnQueue(false);

            Visibility visibility = VISIBILITY.getVisibility();
            VisibilityJson visibilityJson = new VisibilityJson();
            visibilityJson.setSource(visibility.getVisibilityString());

            VertexBuilder builder = prepareVertex(ID_PREFIX_RELATIONSHIP, relationshipIRI, workspaceId, visibility, visibilityJson);

            Date modifiedDate = new Date();
            Vertex relationshipVertex = ctx.update(builder, modifiedDate, visibilityJson, TYPE_RELATIONSHIP, elemCtx -> {
                Metadata metadata = getMetadata(modifiedDate, user, visibility);
                OntologyProperties.ONTOLOGY_TITLE.updateProperty(elemCtx, relationshipIRI, metadata, visibility);
                if (displayName != null) {
                    OntologyProperties.DISPLAY_NAME.updateProperty(elemCtx, displayName, metadata, visibility);
                }
            }).get();

            for (Concept domainConcept : domainConcepts) {
                findOrAddEdge(ctx, ((VertexiumConcept) domainConcept).getVertex(), relationshipVertex, LabelName.HAS_EDGE.toString());
            }

            for (Concept rangeConcept : rangeConcepts) {
                findOrAddEdge(ctx, relationshipVertex, ((VertexiumConcept) rangeConcept).getVertex(), LabelName.HAS_EDGE.toString());
            }

            if (parent != null) {
                findOrAddEdge(ctx, relationshipVertex, ((VertexiumRelationship) parent).getVertex(), LabelName.IS_A.toString());
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

            if (workspaceId != null) {
                findOrAddEdge(ctx, workspaceId, relationshipVertex.getId(), WorkspaceProperties.WORKSPACE_TO_ONTOLOGY_RELATIONSHIP_IRI);
            }

            Collection<OntologyProperty> properties = new ArrayList<>();
            String parentIRI = parent == null ? null : parent.getIRI();
            return createRelationship(parentIRI, relationshipVertex, inverseOfIRIs, domainConceptIris, rangeConceptIris, properties, workspaceId);
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
        Authorizations authorizations = getAuthorizations(workspaceId);

        OntologyProperty typeProperty = getPropertyByIRI(propertyIri, workspaceId);
        Vertex propertyVertex;
        if (typeProperty == null) {
            definePropertyOnGraph(graph, propertyIri, dataType, textIndexHints, boost, sortable);

            try (GraphUpdateContext ctx = graphRepository.beginGraphUpdate(getPriority(user), user, authorizations)) {
                ctx.setPushOnQueue(false);

                Visibility visibility = VISIBILITY.getVisibility();
                VisibilityJson visibilityJson = new VisibilityJson();
                visibilityJson.setSource(visibility.getVisibilityString());

                VertexBuilder builder = prepareVertex(ID_PREFIX_PROPERTY, propertyIri, workspaceId, visibility, visibilityJson);
                Date modifiedDate = new Date();
                propertyVertex = ctx.update(builder, modifiedDate, visibilityJson, TYPE_PROPERTY, elemCtx -> {
                    Metadata metadata = getMetadata(modifiedDate, user, visibility);
                    OntologyProperties.ONTOLOGY_TITLE.updateProperty(elemCtx, propertyIri, metadata, visibility);
                    OntologyProperties.DATA_TYPE.updateProperty(elemCtx, dataType.toString(), metadata, visibility);
                    if (possibleValues != null) {
                        OntologyProperties.POSSIBLE_VALUES.updateProperty(elemCtx, JSONUtil.toJson(possibleValues), metadata, visibility);
                    }
                    if (textIndexHints != null && textIndexHints.size() > 0) {
                        textIndexHints.forEach(i -> {
                            String textIndexHint = i.toString();
                            OntologyProperties.TEXT_INDEX_HINTS.updateProperty(elemCtx, textIndexHint, textIndexHint, metadata, visibility);
                        });
                    }
                }).get();

                for (Concept concept : concepts) {
                    checkNotNull(concept, "concepts cannot have null values");
                    findOrAddEdge(ctx, ((VertexiumConcept) concept).getVertex(), propertyVertex, LabelName.HAS_PROPERTY.toString());
                }
                for (Relationship relationship : relationships) {
                    checkNotNull(relationships, "relationships cannot have null values");
                    findOrAddEdge(ctx, ((VertexiumRelationship) relationship).getVertex(), propertyVertex, LabelName.HAS_PROPERTY.toString());
                }


                if (workspaceId != null) {
                    findOrAddEdge(ctx, workspaceId, propertyVertex.getId(), WorkspaceProperties.WORKSPACE_TO_ONTOLOGY_RELATIONSHIP_IRI);
                }
            } catch (Exception e) {
                throw new VisalloException("Could not getOrCreatePropertyVertex: " + propertyIri, e);
            }
        } else {
            propertyVertex = ((VertexiumOntologyProperty) typeProperty).getVertex();
            deleteChangeableProperties(typeProperty, authorizations);
        }
        return propertyVertex;
    }

    private Priority getPriority(User user) {
        return user == null ? Priority.LOW : Priority.NORMAL;
    }

    private void saveDependentProperties(String propertyVertexId, Collection<String> dependentPropertyIris, User user, String workspaceId) {
        Authorizations authorizations = getAuthorizations(workspaceId);

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

        Iterable<EdgeVertexPair> existingConcepts = vertexiumProperty.getVertex().getEdgeVertexPairs(Direction.BOTH, LabelName.HAS_PROPERTY.toString(), getAuthorizations(workspaceId));
        for (EdgeVertexPair existingConcept : existingConcepts) {
            String conceptIri = OntologyProperties.ONTOLOGY_TITLE.getPropertyValue(existingConcept.getVertex());
            if (!domainIris.remove(conceptIri)) {
                getGraph().softDeleteEdge(existingConcept.getEdge(), getAuthorizations(workspaceId));
            }
        }

        for (String domainIri : domainIris) {
            Vertex domainVertex;
            Concept concept = getConceptByIRI(domainIri, workspaceId);
            if (concept != null) {
                domainVertex = ((VertexiumConcept) concept).getVertex();
            } else {
                Relationship relationship = getRelationshipByIRI(domainIri, workspaceId);
                if (relationship != null) {
                    domainVertex = ((VertexiumRelationship) relationship).getVertex();
                } else {
                    throw new VisalloException("Could not find domain with IRI " + domainIri);
                }
            }
            findOrAddEdge(domainVertex, ((VertexiumOntologyProperty) property).getVertex(), LabelName.HAS_PROPERTY.toString(), user, workspaceId);
        }
    }

    private Vertex getParentVertex(Vertex vertex, String workspaceId) {
        try {
            return Iterables.getOnlyElement(vertex.getVertices(Direction.OUT, LabelName.IS_A.toString(), getAuthorizations(workspaceId)), null);
        } catch (IllegalArgumentException iae) {
            throw new IllegalStateException(String.format(
                    "Unexpected number of parents for concept %s",
                    OntologyProperties.TITLE.getPropertyValue(vertex)
            ), iae);
        }
    }

    protected Authorizations getAuthorizations(String workspaceId, String... otherAuthorizations) {
        if (workspaceId == null && (otherAuthorizations == null || otherAuthorizations.length == 0)) {
            return publicOntologyAuthorizations;
        }

        if (workspaceId == null) {
            return graph.createAuthorizations(publicOntologyAuthorizations, otherAuthorizations);
        } else if (otherAuthorizations == null || otherAuthorizations.length == 0) {
            return graph.createAuthorizations(publicOntologyAuthorizations, workspaceId);
        }
        return graph.createAuthorizations(publicOntologyAuthorizations, ArrayUtils.add(otherAuthorizations, workspaceId));
    }

    protected Graph getGraph() {
        return graph;
    }

    /**
     * Overridable so subclasses can supply a custom implementation of OntologyProperty.
     */
    protected OntologyProperty createOntologyProperty(
            Vertex propertyVertex,
            ImmutableList<String> dependentPropertyIris,
            PropertyType propertyType,
            String workspaceId
    ) {
        if (propertyType.equals(PropertyType.EXTENDED_DATA_TABLE)) {
            Authorizations authorizations = getAuthorizations(workspaceId);
            VertexiumExtendedDataTableOntologyProperty result = new VertexiumExtendedDataTableOntologyProperty(propertyVertex, dependentPropertyIris, workspaceId);
            Iterable<String> tablePropertyIris = propertyVertex.getVertexIds(Direction.OUT, LabelName.HAS_PROPERTY.toString(), authorizations);
            for (String tablePropertyIri : tablePropertyIris) {
                result.addProperty(tablePropertyIri.substring(VertexiumOntologyRepository.ID_PREFIX_PROPERTY.length()));
            }
            return result;
        } else {
            return new VertexiumOntologyProperty(propertyVertex, dependentPropertyIris, workspaceId);
        }
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
            Collection<OntologyProperty> properties,
            String workspaceId
    ) {
        return new VertexiumRelationship(
                parentIRI,
                relationshipVertex,
                domainConceptIris,
                rangeConceptIris,
                inverseOfIRIs,
                properties,
                workspaceId
        );
    }

    /**
     * Overridable so subclasses can supply a custom implementation of Concept.
     */
    protected Concept createConcept(Vertex vertex, List<OntologyProperty> conceptProperties, String parentConceptIRI, String workspaceId) {
        return new VertexiumConcept(vertex, parentConceptIRI, conceptProperties, workspaceId);
    }

    /**
     * Overridable so subclasses can supply a custom implementation of Concept.
     */
    protected Concept createConcept(Vertex vertex, String workspaceId) {
        return new VertexiumConcept(vertex, workspaceId);
    }

    @Override
    protected void deleteChangeableProperties(OntologyProperty property, Authorizations authorizations) {
        Vertex vertex = ((VertexiumOntologyProperty) property).getVertex();
        deleteChangeableProperties(vertex, authorizations);
    }

    @Override
    protected void deleteChangeableProperties(OntologyElement element, Authorizations authorizations) {
        Vertex vertex = element instanceof VertexiumConcept ? ((VertexiumConcept) element).getVertex() : ((VertexiumRelationship) element).getVertex();
        deleteChangeableProperties(vertex, authorizations);
    }

    private void deleteChangeableProperties(Vertex vertex, Authorizations authorizations) {
        for (Property property : vertex.getProperties()) {
            if (OntologyProperties.CHANGEABLE_PROPERTY_IRI.contains(property.getName())) {
                vertex.softDeleteProperty(property.getKey(), property.getName(), authorizations);
            }
        }
        graph.flush();
    }

    private List<OntologyProperty> transformProperties(Iterable<Vertex> vertices, String workspaceId) {
        return StreamSupport.stream(vertices.spliterator(), false)
                .filter(vertex -> VisalloProperties.CONCEPT_TYPE.getPropertyValue(vertex, "").equals(TYPE_PROPERTY))
                .map(vertex -> {
                    ImmutableList<String> dependentPropertyIris = getDependentPropertyIris(vertex, workspaceId);
                    PropertyType dataType = VertexiumOntologyProperty.getDataType(vertex);
                    return createOntologyProperty(vertex, dependentPropertyIris, dataType, workspaceId);
                })
                .collect(Collectors.toList());
    }

    private List<Concept> transformConcepts(Iterable<Vertex> vertices, String workspaceId) {
        Authorizations authorizations = getAuthorizations(workspaceId);

        List<Vertex> filtered = StreamSupport.stream(vertices.spliterator(), false)
                .filter(vertex -> VisalloProperties.CONCEPT_TYPE.getPropertyValue(vertex, "").equals(TYPE_CONCEPT))
                .collect(Collectors.toList());

        Map<String, String> parentVertexIdToIRI = buildParentIdToIriMap(filtered, authorizations);

        return filtered.stream().map(vertex -> {
            String parentVertexId = getParentVertexId(vertex, authorizations);
            String parentIRI = parentVertexId == null ? null : parentVertexIdToIRI.get(parentVertexId);

            List<OntologyProperty> conceptProperties = getPropertiesByVertexNoRecursion(vertex, workspaceId);
            return createConcept(vertex, conceptProperties, parentIRI, workspaceId);
        }).collect(Collectors.toList());
    }

    private List<Relationship> transformRelationships(Iterable<Vertex> vertices, String workspaceId) {
        Authorizations authorizations = getAuthorizations(workspaceId);

        List<Vertex> filtered = StreamSupport.stream(vertices.spliterator(), false)
                .filter(vertex -> VisalloProperties.CONCEPT_TYPE.getPropertyValue(vertex, "").equals(TYPE_RELATIONSHIP))
                .collect(Collectors.toList());

        Map<String, String> parentVertexIdToIRI = buildParentIdToIriMap(filtered, authorizations);

        return filtered.stream().map(vertex -> {
            String parentVertexId = getParentVertexId(vertex, authorizations);
            String parentIRI = parentVertexId == null ? null : parentVertexIdToIRI.get(parentVertexId);
            return toVertexiumRelationship(parentIRI, vertex, workspaceId);
        }).collect(Collectors.toList());
    }

    private String getParentVertexId(Vertex vertex, Authorizations authorizations) {
        Iterable<EdgeInfo> parentEdgeInfos = vertex.getEdgeInfos(Direction.OUT, LabelName.IS_A.toString(), authorizations);
        EdgeInfo parentEdge = parentEdgeInfos == null ? null : Iterables.getOnlyElement(parentEdgeInfos);
        return parentEdge == null ? null : parentEdge.getVertexId();
    }

    private Map<String, String> buildParentIdToIriMap(Iterable<Vertex> vertices, Authorizations authorizations) {
        Set<String> parentVertexIds = StreamSupport.stream(vertices.spliterator(), false)
                .map(vertex -> vertex.getEdgeInfos(Direction.OUT, LabelName.IS_A.toString(), authorizations))
                .filter(Objects::nonNull)
                .map(Iterables::getOnlyElement)
                .filter(Objects::nonNull)
                .map(EdgeInfo::getVertexId)
                .collect(Collectors.toSet());

        Iterable<Vertex> parentVertices = graph.getVertices(parentVertexIds, EnumSet.of(FetchHint.PROPERTIES), authorizations);

        Map<String, String> vertexIdToIri = StreamSupport.stream(parentVertices.spliterator(), false)
                .collect(Collectors.toMap(Vertex::getId, OntologyProperties.ONTOLOGY_TITLE::getPropertyValue));

        CloseableUtils.closeQuietly(parentVertices);

        return vertexIdToIri;
    }

    @Override
    public void internalPublishConcept(Concept concept, User user, String workspaceId) {
        assert (concept instanceof VertexiumConcept);
        if (concept.getSandboxStatus() != SandboxStatus.PUBLIC) {
            Vertex vertex = ((VertexiumConcept) concept).getVertex();
            VisibilityJson visibilityJson = VisalloProperties.VISIBILITY_JSON.getPropertyValue(vertex);
            if (visibilityJson != null && visibilityJson.getWorkspaces().contains(workspaceId)) {
                visibilityJson = VisibilityJson.removeFromAllWorkspace(visibilityJson);
                VisalloVisibility visalloVisibility = visibilityTranslator.toVisibility(visibilityJson);
                try (GraphUpdateContext ctx = graphRepository.beginGraphUpdate(Priority.NORMAL, user, getAuthorizations(workspaceId))) {
                    ctx.update(vertex, new Date(), visibilityJson, null, vertexUpdateCtx -> {
                        ExistingElementMutation<Vertex> mutation = (ExistingElementMutation<Vertex>) vertexUpdateCtx.getMutation();
                        mutation.alterElementVisibility(visalloVisibility.getVisibility());
                    });
                    removeEdge(ctx, workspaceId, vertex.getId());
                }
            }
        }
    }

    @Override
    public void internalPublishRelationship(Relationship relationship, User user, String workspaceId) {
        assert (relationship instanceof VertexiumRelationship);
        if (relationship.getSandboxStatus() != SandboxStatus.PUBLIC) {
            Vertex vertex = ((VertexiumRelationship) relationship).getVertex();
            VisibilityJson visibilityJson = VisalloProperties.VISIBILITY_JSON.getPropertyValue(vertex);
            if (visibilityJson != null && visibilityJson.getWorkspaces().contains(workspaceId)) {
                visibilityJson = VisibilityJson.removeFromAllWorkspace(visibilityJson);
                VisalloVisibility visalloVisibility = visibilityTranslator.toVisibility(visibilityJson);
                try (GraphUpdateContext ctx = graphRepository.beginGraphUpdate(Priority.NORMAL, user, getAuthorizations(workspaceId))) {
                    ctx.update(vertex, new Date(), visibilityJson, null, vertexUpdateCtx -> {
                        ExistingElementMutation<Vertex> mutation = (ExistingElementMutation<Vertex>) vertexUpdateCtx.getMutation();
                        mutation.alterElementVisibility(visalloVisibility.getVisibility());
                    });
                    removeEdge(ctx, workspaceId, vertex.getId());
                }
            }
        }
    }

    @Override
    public void internalPublishProperty(OntologyProperty property, User user, String workspaceId) {
        assert (property instanceof VertexiumOntologyProperty);
        if (property.getSandboxStatus() != SandboxStatus.PUBLIC) {
            Vertex vertex = ((VertexiumOntologyProperty) property).getVertex();
            VisibilityJson visibilityJson = VisalloProperties.VISIBILITY_JSON.getPropertyValue(vertex);
            if (visibilityJson != null && visibilityJson.getWorkspaces().contains(workspaceId)) {
                visibilityJson = VisibilityJson.removeFromAllWorkspace(visibilityJson);
                VisalloVisibility visalloVisibility = visibilityTranslator.toVisibility(visibilityJson);
                try (GraphUpdateContext ctx = graphRepository.beginGraphUpdate(Priority.NORMAL, user, getAuthorizations(workspaceId))) {
                    ctx.update(vertex, new Date(), visibilityJson, null, vertexUpdateCtx -> {
                        ExistingElementMutation<Vertex> mutation = (ExistingElementMutation<Vertex>) vertexUpdateCtx.getMutation();
                        mutation.alterElementVisibility(visalloVisibility.getVisibility());
                    });
                    removeEdge(ctx, workspaceId, vertex.getId());
                }
            }
        }
    }

    private String cacheKey(String workspaceId) {
        return (workspaceId == null ? "" : workspaceId);
    }
}
