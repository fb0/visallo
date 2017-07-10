package org.visallo.core.model.ontology;

import com.beust.jcommander.internal.Lists;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import org.apache.commons.compress.utils.IOUtils;
import org.junit.Before;
import org.junit.Test;
import org.semanticweb.owlapi.model.IRI;
import org.vertexium.Authorizations;
import org.vertexium.TextIndexHint;
import org.visallo.core.exception.VisalloAccessDeniedException;
import org.visallo.core.user.SystemUser;
import org.visallo.core.user.User;
import org.visallo.core.util.VisalloInMemoryTestBase;
import org.visallo.web.clientapi.model.ClientApiOntology;
import org.visallo.web.clientapi.model.Privilege;
import org.visallo.web.clientapi.model.PropertyType;
import org.visallo.web.clientapi.model.SandboxStatus;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public abstract class OntologyRepositoryTestBase extends VisalloInMemoryTestBase {
    private static final String TEST_OWL = "test.owl";
    private static final String TEST_CHANGED_OWL = "test_changed.owl";
    private static final String TEST01_OWL = "test01.owl";
    private static final String GLYPH_ICON_FILE = "glyphicons_003_user@2x.png";
    private static final String TEST_IRI = "http://visallo.org/test";

    private static final String TEST_HIERARCHY_IRI = "http://visallo.org/testhierarchy";
    private static final String TEST_HIERARCHY_OWL = "test_hierarchy.owl";

    private static final String TEST01_IRI = "http://visallo.org/test01";

    private static final String SANDBOX_CONCEPT_IRI = "sandbox-concept-iri";
    private static final String SANDBOX_RELATIONSHIP_IRI = "sandbox-relationship-iri";
    private static final String SANDBOX_PROPERTY_IRI = "sandbox-property-iri";
    private static final String SANDBOX_DISPLAY_NAME = "Sandbox Display";
    private static final String PUBLIC_CONCEPT_IRI = "public-concept-iri";
    private static final String PUBLIC_RELATIONSHIP_IRI = "public-relationship-iri";
    private static final String PUBLIC_PROPERTY_IRI = "public-property-iri";
    private static final String PUBLIC_DISPLAY_NAME = "Public Display";

    private String workspaceId = "junit-workspace";
    private User systemUser = new SystemUser();

    private Authorizations authorizations;
    private User user;

    @Before
    public void before() {
        super.before();
        authorizations = getGraph().createAuthorizations();
        user = getUserRepository().findOrAddUser("junit", "Junit", "junit@visallo.com", "password");
    }

    @Test
    public void testChangingDisplayAnnotationsShouldSucceed() throws Exception {
        loadTestOwlFile();

        File changedOwl = new File(OntologyRepositoryTestBase.class.getResource(TEST_CHANGED_OWL).toURI());

        getOntologyRepository().importFile(changedOwl, IRI.create(TEST_IRI), authorizations);

        validateChangedOwlRelationships();
        validateChangedOwlConcepts();
        validateChangedOwlProperties();
    }

    @Test
    public void testGettingParentConceptReturnsParentProperties() throws Exception {
        loadHierarchyOwlFile();
        Concept concept = getOntologyRepository().getConceptByIRI(TEST_HIERARCHY_IRI + "#person", null);
        Concept parentConcept = getOntologyRepository().getParentConcept(concept, null);
        assertEquals(1, parentConcept.getProperties().size());
    }

    @Test
    public void testRelationshipHierarchy() throws Exception {
        loadHierarchyOwlFile();

        Relationship relationship = getOntologyRepository().getRelationshipByIRI(TEST_HIERARCHY_IRI + "#personReallyKnowsPerson", null);
        assertEquals(TEST_HIERARCHY_IRI + "#personKnowsPerson", relationship.getParentIRI());

        relationship = getOntologyRepository().getParentRelationship(relationship, null);
        assertEquals(TEST_HIERARCHY_IRI + "#personKnowsPerson", relationship.getIRI());
        assertEquals(OntologyRepositoryBase.TOP_OBJECT_PROPERTY_IRI, relationship.getParentIRI());
    }

    @Test
    public void testDependenciesBetweenOntologyFilesShouldNotChangeParentProperties() throws Exception {
        loadTestOwlFile();

        File changedOwl = new File(OntologyRepositoryTestBase.class.getResource(TEST01_OWL).toURI());

        getOntologyRepository().importFile(changedOwl, IRI.create(TEST01_IRI), authorizations);
        validateTestOwlRelationship();
        validateTestOwlConcepts(3);
        validateTestOwlProperties();

        OntologyProperty aliasProperty = getOntologyRepository().getPropertyByIRI(TEST01_IRI + "#alias", null);
        Concept person = getOntologyRepository().getConceptByIRI(TEST_IRI + "#person", null);
        assertTrue(person.getProperties()
                .stream()
                .anyMatch(p -> p.getIri().equals(aliasProperty.getIri()))
        );
    }

    @Test
    public void testGetConceptsWithProperties() throws Exception {
        loadHierarchyOwlFile();
        getOntologyRepository().clearCache();

        Iterable<Concept> conceptsWithProperties = getOntologyRepository().getConceptsWithProperties(workspaceId);
        Map<String, Concept> conceptsByIri = StreamSupport.stream(conceptsWithProperties.spliterator(), false)
                .collect(Collectors.toMap(Concept::getIRI, Function.identity()));

        Concept personConcept = conceptsByIri.get("http://visallo.org/testhierarchy#person");

        // Check parent iris
        assertNull(conceptsByIri.get("http://visallo.org#root").getParentConceptIRI());
        assertEquals("http://visallo.org#root", conceptsByIri.get("http://www.w3.org/2002/07/owl#Thing").getParentConceptIRI());
        assertEquals("http://www.w3.org/2002/07/owl#Thing", conceptsByIri.get("http://visallo.org/testhierarchy#contact").getParentConceptIRI());
        assertEquals("http://visallo.org/testhierarchy#contact", personConcept.getParentConceptIRI());

        // Check properties
        List<OntologyProperty> personProperties = new ArrayList<>(personConcept.getProperties());
        assertEquals(1, personProperties.size());
        assertEquals("http://visallo.org/testhierarchy#name", personProperties.get(0).getIri());

        // Check intents
        List<String> intents = Arrays.asList(personConcept.getIntents());
        assertEquals(2, intents.size());
        assertTrue(intents.contains("face"));
        assertTrue(intents.contains("person"));

        // Spot check other concept values
        assertEquals("Person", personConcept.getDisplayName());
        assertEquals("prop('http://visallo.org/testhierarchy#name') || ''", personConcept.getTitleFormula());
    }

    @Test
    public void testGetRelationships() throws Exception {
        loadHierarchyOwlFile();
        getOntologyRepository().clearCache();

        Iterable<Relationship> relationships = getOntologyRepository().getRelationships(workspaceId);
        Map<String, Relationship> relationshipsByIri = StreamSupport.stream(relationships.spliterator(), false)
                .collect(Collectors.toMap(Relationship::getIRI, Function.identity()));

        assertNull(relationshipsByIri.get("http://www.w3.org/2002/07/owl#topObjectProperty").getParentIRI());
        assertEquals("http://www.w3.org/2002/07/owl#topObjectProperty", relationshipsByIri.get("http://visallo.org/testhierarchy#personKnowsPerson").getParentIRI());
    }

    @Test
    public void testClientApiObjectWithUnknownWorkspace() throws Exception {
        createSampleOntology();

        ClientApiOntology clientApiObject = getOntologyRepository().getClientApiObject("unknown-workspace");

        assertFalse(clientApiObject.getConcepts().stream().anyMatch(concept -> concept.getTitle().equals(SANDBOX_CONCEPT_IRI)));
        ClientApiOntology.Concept publicApiConcept = clientApiObject.getConcepts().stream().filter(concept -> concept.getTitle().equals(PUBLIC_CONCEPT_IRI)).findFirst().get();
        assertEquals(SandboxStatus.PUBLIC, publicApiConcept.getSandboxStatus());

        assertFalse(clientApiObject.getRelationships().stream().anyMatch(relationship -> relationship.getTitle().equals(SANDBOX_RELATIONSHIP_IRI)));
        ClientApiOntology.Relationship publicApiRelationship = clientApiObject.getRelationships().stream().filter(relationship -> relationship.getTitle().equals(PUBLIC_RELATIONSHIP_IRI)).findFirst().get();
        assertEquals(SandboxStatus.PUBLIC, publicApiRelationship.getSandboxStatus());

        assertFalse(clientApiObject.getProperties().stream().anyMatch(property -> property.getTitle().equals(SANDBOX_PROPERTY_IRI)));
        ClientApiOntology.Property publicApiProperty = clientApiObject.getProperties().stream().filter(property -> property.getTitle().equals(PUBLIC_PROPERTY_IRI)).findFirst().get();
        assertEquals(SandboxStatus.PUBLIC, publicApiProperty.getSandboxStatus());
    }

    @Test
    public void testClientApiObjectWithNoWorkspace() throws Exception {
        createSampleOntology();

        ClientApiOntology clientApiObject = getOntologyRepository().getClientApiObject(null);

        assertFalse(clientApiObject.getConcepts().stream().anyMatch(concept -> concept.getTitle().equals(SANDBOX_CONCEPT_IRI)));
        ClientApiOntology.Concept publicApiConcept = clientApiObject.getConcepts().stream().filter(concept -> concept.getTitle().equals(PUBLIC_CONCEPT_IRI)).findFirst().get();
        assertEquals(SandboxStatus.PUBLIC, publicApiConcept.getSandboxStatus());

        assertFalse(clientApiObject.getRelationships().stream().anyMatch(relationship -> relationship.getTitle().equals(SANDBOX_RELATIONSHIP_IRI)));
        ClientApiOntology.Relationship publicApiRelationship = clientApiObject.getRelationships().stream().filter(relationship -> relationship.getTitle().equals(PUBLIC_RELATIONSHIP_IRI)).findFirst().get();
        assertEquals(SandboxStatus.PUBLIC, publicApiRelationship.getSandboxStatus());

        assertFalse(clientApiObject.getProperties().stream().anyMatch(property -> property.getTitle().equals(SANDBOX_PROPERTY_IRI)));
        ClientApiOntology.Property publicApiProperty = clientApiObject.getProperties().stream().filter(property -> property.getTitle().equals(PUBLIC_PROPERTY_IRI)).findFirst().get();
        assertEquals(SandboxStatus.PUBLIC, publicApiProperty.getSandboxStatus());
    }

    @Test
    public void testClientApiObjectWithValidWorkspace() throws Exception {
        createSampleOntology();

        ClientApiOntology clientApiObject = getOntologyRepository().getClientApiObject(workspaceId);

        ClientApiOntology.Concept sandboxApiConcept = clientApiObject.getConcepts().stream().filter(concept -> concept.getTitle().equals(SANDBOX_CONCEPT_IRI)).findFirst().get();
        assertEquals(SandboxStatus.PRIVATE, sandboxApiConcept.getSandboxStatus());
        ClientApiOntology.Concept publicApiConcept = clientApiObject.getConcepts().stream().filter(concept -> concept.getTitle().equals(PUBLIC_CONCEPT_IRI)).findFirst().get();
        assertEquals(SandboxStatus.PUBLIC, publicApiConcept.getSandboxStatus());

        ClientApiOntology.Relationship sandboxApiRelationship = clientApiObject.getRelationships().stream().filter(relationship -> relationship.getTitle().equals(SANDBOX_RELATIONSHIP_IRI)).findFirst().get();
        assertEquals(SandboxStatus.PRIVATE, sandboxApiRelationship.getSandboxStatus());
        ClientApiOntology.Relationship publicApiRelationship = clientApiObject.getRelationships().stream().filter(relationship -> relationship.getTitle().equals(PUBLIC_RELATIONSHIP_IRI)).findFirst().get();
        assertEquals(SandboxStatus.PUBLIC, publicApiRelationship.getSandboxStatus());

        ClientApiOntology.Property sandboxApiProperty = clientApiObject.getProperties().stream().filter(property -> property.getTitle().equals(SANDBOX_PROPERTY_IRI)).findFirst().get();
        assertEquals(SandboxStatus.PRIVATE, sandboxApiProperty.getSandboxStatus());
        ClientApiOntology.Property publicApiProperty = clientApiObject.getProperties().stream().filter(property -> property.getTitle().equals(PUBLIC_PROPERTY_IRI)).findFirst().get();
        assertEquals(SandboxStatus.PUBLIC, publicApiProperty.getSandboxStatus());
    }

    @Test(expected = VisalloAccessDeniedException.class)
    public void testCreatingConceptsWithNoUserOrWorkspace() {
        Concept thing = getOntologyRepository().getEntityConcept(workspaceId);
        getOntologyRepository().getOrCreateConcept(thing, PUBLIC_CONCEPT_IRI, PUBLIC_DISPLAY_NAME, null, null, null);
    }

    @Test(expected = VisalloAccessDeniedException.class)
    public void testCreatingPublicConceptsWithoutPublishPrivilege() {
        Concept thing = getOntologyRepository().getEntityConcept(workspaceId);
        getOntologyRepository().getOrCreateConcept(thing, PUBLIC_CONCEPT_IRI, PUBLIC_DISPLAY_NAME, null, user, null);
    }

    @Test
    public void testCreatingPublicConcepts() {
        setPrivileges(user, Sets.newHashSet(Privilege.ONTOLOGY_ADD, Privilege.ONTOLOGY_PUBLISH));

        Concept thing = getOntologyRepository().getEntityConcept(workspaceId);
        getOntologyRepository().getOrCreateConcept(thing, PUBLIC_CONCEPT_IRI, PUBLIC_DISPLAY_NAME, null, user, null);
        getOntologyRepository().clearCache();

        Concept noWorkspace = getOntologyRepository().getConceptByIRI(PUBLIC_CONCEPT_IRI, null);
        assertEquals(PUBLIC_DISPLAY_NAME, noWorkspace.getDisplayName());

        Concept withWorkspace = getOntologyRepository().getConceptByIRI(PUBLIC_CONCEPT_IRI, workspaceId);
        assertEquals(PUBLIC_DISPLAY_NAME, withWorkspace.getDisplayName());
    }

    @Test
    public void testCreatingPublicConceptsAsSystem() {
        Concept thing = getOntologyRepository().getEntityConcept(workspaceId);
        getOntologyRepository().getOrCreateConcept(thing, PUBLIC_CONCEPT_IRI, PUBLIC_DISPLAY_NAME, null, systemUser, null);
        getOntologyRepository().clearCache();

        Concept noWorkspace = getOntologyRepository().getConceptByIRI(PUBLIC_CONCEPT_IRI, null);
        assertEquals(PUBLIC_DISPLAY_NAME, noWorkspace.getDisplayName());

        Concept withWorkspace = getOntologyRepository().getConceptByIRI(PUBLIC_CONCEPT_IRI, workspaceId);
        assertEquals(PUBLIC_DISPLAY_NAME, withWorkspace.getDisplayName());
    }

    @Test(expected = VisalloAccessDeniedException.class)
    public void testCreatingSandboxedConceptsWithoutAddPermissionPrivilege() {
        Concept thing = getOntologyRepository().getEntityConcept(workspaceId);
        getOntologyRepository().getOrCreateConcept(thing, SANDBOX_CONCEPT_IRI, SANDBOX_DISPLAY_NAME, null, user, workspaceId);
    }

    @Test
    public void testCreatingSandboxedConcepts() {
        setPrivileges(user, Collections.singleton(Privilege.ONTOLOGY_ADD));

        Concept thing = getOntologyRepository().getEntityConcept(workspaceId);
        getOntologyRepository().getOrCreateConcept(thing, SANDBOX_CONCEPT_IRI, SANDBOX_DISPLAY_NAME, null, user, workspaceId);
        getOntologyRepository().clearCache();

        Concept noWorkspace = getOntologyRepository().getConceptByIRI(SANDBOX_CONCEPT_IRI, null);
        assertNull(noWorkspace);

        Concept withWorkspace = getOntologyRepository().getConceptByIRI(SANDBOX_CONCEPT_IRI, workspaceId);
        assertEquals(SANDBOX_DISPLAY_NAME, withWorkspace.getDisplayName());
    }

    @Test(expected = VisalloAccessDeniedException.class)
    public void testPublishingConceptsWithoutPublishPrivilege() {
        setPrivileges(user, Collections.singleton(Privilege.ONTOLOGY_ADD));

        Concept thing = getOntologyRepository().getEntityConcept(workspaceId);
        Concept sandboxedConcept = getOntologyRepository().getOrCreateConcept(thing, SANDBOX_CONCEPT_IRI, SANDBOX_DISPLAY_NAME, null, user, workspaceId);
        getOntologyRepository().clearCache();

        getOntologyRepository().publishConcept(sandboxedConcept, user, workspaceId);
    }

    @Test
    public void testPublishingConcepts() {
        setPrivileges(user, Sets.newHashSet(Privilege.ONTOLOGY_ADD, Privilege.ONTOLOGY_PUBLISH));

        Concept thing = getOntologyRepository().getEntityConcept(workspaceId);
        Concept sandboxedConcept = getOntologyRepository().getOrCreateConcept(thing, SANDBOX_CONCEPT_IRI, SANDBOX_DISPLAY_NAME, null, user, workspaceId);
        getOntologyRepository().publishConcept(sandboxedConcept, user, workspaceId);
        getOntologyRepository().clearCache();

        Concept publicConcept = getOntologyRepository().getConceptByIRI(SANDBOX_CONCEPT_IRI, null);
        assertEquals(SANDBOX_DISPLAY_NAME, publicConcept.getDisplayName());
    }

    @Test(expected = VisalloAccessDeniedException.class)
    public void testCreatingRelationshipsWithNoUserOrWorkspace() {
        List<Concept> thing = Collections.singletonList(getOntologyRepository().getEntityConcept(workspaceId));
        getOntologyRepository().getOrCreateRelationshipType(null, thing, thing, PUBLIC_RELATIONSHIP_IRI, null, true, null, null);
    }

    @Test(expected = VisalloAccessDeniedException.class)
    public void testCreatingPublicRelationshipsWithoutPublishPrivilege() {
        List<Concept> thing = Collections.singletonList(getOntologyRepository().getEntityConcept(workspaceId));
        getOntologyRepository().getOrCreateRelationshipType(null, thing, thing, PUBLIC_RELATIONSHIP_IRI, null, true, user, null);
    }

    @Test
    public void testCreatingPublicRelationships() {
        setPrivileges(user, Sets.newHashSet(Privilege.ONTOLOGY_ADD, Privilege.ONTOLOGY_PUBLISH));

        List<Concept> thing = Collections.singletonList(getOntologyRepository().getEntityConcept(workspaceId));
        getOntologyRepository().getOrCreateRelationshipType(null, thing, thing, PUBLIC_RELATIONSHIP_IRI, null, true, user, null);
        getOntologyRepository().clearCache();

        Relationship noWorkspace = getOntologyRepository().getRelationshipByIRI(PUBLIC_RELATIONSHIP_IRI, null);
        assertEquals(PUBLIC_RELATIONSHIP_IRI, noWorkspace.getIRI());

        Relationship withWorkspace = getOntologyRepository().getRelationshipByIRI(PUBLIC_RELATIONSHIP_IRI, workspaceId);
        assertEquals(PUBLIC_RELATIONSHIP_IRI, withWorkspace.getIRI());
    }

    @Test
    public void testCreatingPublicRelationshipsAsSystem() {
        List<Concept> thing = Collections.singletonList(getOntologyRepository().getEntityConcept(workspaceId));
        getOntologyRepository().getOrCreateRelationshipType(null, thing, thing, PUBLIC_RELATIONSHIP_IRI, null, true, systemUser, null);
        getOntologyRepository().clearCache();

        Relationship noWorkspace = getOntologyRepository().getRelationshipByIRI(PUBLIC_RELATIONSHIP_IRI, null);
        assertEquals(PUBLIC_RELATIONSHIP_IRI, noWorkspace.getIRI());

        Relationship withWorkspace = getOntologyRepository().getRelationshipByIRI(PUBLIC_RELATIONSHIP_IRI, workspaceId);
        assertEquals(PUBLIC_RELATIONSHIP_IRI, withWorkspace.getIRI());
    }

    @Test(expected = VisalloAccessDeniedException.class)
    public void testCreatingSandboxedRelationshipsWithoutAddPermissionPrivilege() {
        List<Concept> thing = Collections.singletonList(getOntologyRepository().getEntityConcept(workspaceId));
        getOntologyRepository().getOrCreateRelationshipType(null, thing, thing, SANDBOX_RELATIONSHIP_IRI, null, true, user, workspaceId);
    }

    @Test
    public void testCreatingSandboxedRelationships() {
        setPrivileges(user, Collections.singleton(Privilege.ONTOLOGY_ADD));

        List<Concept> thing = Collections.singletonList(getOntologyRepository().getEntityConcept(workspaceId));
        getOntologyRepository().getOrCreateRelationshipType(null, thing, thing, SANDBOX_RELATIONSHIP_IRI, null, true, user, workspaceId);
        getOntologyRepository().clearCache();

        Relationship noWorkspace = getOntologyRepository().getRelationshipByIRI(SANDBOX_RELATIONSHIP_IRI, null);
        assertNull(noWorkspace);

        Relationship withWorkspace = getOntologyRepository().getRelationshipByIRI(SANDBOX_RELATIONSHIP_IRI, workspaceId);
        assertEquals(SANDBOX_RELATIONSHIP_IRI, withWorkspace.getIRI());
    }

    @Test(expected = VisalloAccessDeniedException.class)
    public void testPublishingRelationshipsWithoutPublishPrivilege() {
        setPrivileges(user, Collections.singleton(Privilege.ONTOLOGY_ADD));

        List<Concept> thing = Collections.singletonList(getOntologyRepository().getEntityConcept(workspaceId));
        Relationship sandboxedRelationship = getOntologyRepository().getOrCreateRelationshipType(null, thing, thing, SANDBOX_RELATIONSHIP_IRI, null, true, user, workspaceId);
        getOntologyRepository().publishRelationship(sandboxedRelationship, user, workspaceId);
    }

    @Test
    public void testPublishingRelationships() {
        setPrivileges(user, Sets.newHashSet(Privilege.ONTOLOGY_ADD, Privilege.ONTOLOGY_PUBLISH));

        List<Concept> thing = Collections.singletonList(getOntologyRepository().getEntityConcept(workspaceId));
        Relationship sandboxedRelationship = getOntologyRepository().getOrCreateRelationshipType(null, thing, thing, SANDBOX_RELATIONSHIP_IRI, null, true, user, workspaceId);
        getOntologyRepository().publishRelationship(sandboxedRelationship, user, workspaceId);
        getOntologyRepository().clearCache();

        Relationship publicRelationship = getOntologyRepository().getRelationshipByIRI(SANDBOX_RELATIONSHIP_IRI, null);
        assertEquals(SANDBOX_RELATIONSHIP_IRI, publicRelationship.getIRI());
    }

    @Test(expected = VisalloAccessDeniedException.class)
    public void testCreatingPropertyWithNoUserOrWorkspace() {
        List<Concept> thing = Collections.singletonList(getOntologyRepository().getEntityConcept(workspaceId));
        OntologyPropertyDefinition ontologyPropertyDefinition = new OntologyPropertyDefinition(thing, PUBLIC_PROPERTY_IRI, SANDBOX_DISPLAY_NAME, PropertyType.DATE);
        getOntologyRepository().getOrCreateProperty(ontologyPropertyDefinition, null, null);
    }

    @Test(expected = VisalloAccessDeniedException.class)
    public void testCreatingPublicPropertyWithoutPublishPrivilege() {
        List<Concept> thing = Collections.singletonList(getOntologyRepository().getEntityConcept(workspaceId));
        OntologyPropertyDefinition ontologyPropertyDefinition = new OntologyPropertyDefinition(thing, PUBLIC_PROPERTY_IRI, SANDBOX_DISPLAY_NAME, PropertyType.DATE);
        getOntologyRepository().getOrCreateProperty(ontologyPropertyDefinition, user, null);
    }

    @Test
    public void testCreatingPublicProperty() {
        setPrivileges(user, Sets.newHashSet(Privilege.ONTOLOGY_ADD, Privilege.ONTOLOGY_PUBLISH));

        List<Concept> thing = Collections.singletonList(getOntologyRepository().getEntityConcept(workspaceId));
        OntologyPropertyDefinition ontologyPropertyDefinition = new OntologyPropertyDefinition(thing, PUBLIC_PROPERTY_IRI, SANDBOX_DISPLAY_NAME, PropertyType.DATE);
        getOntologyRepository().getOrCreateProperty(ontologyPropertyDefinition, user, null);
        getOntologyRepository().clearCache();

        OntologyProperty noWorkspace = getOntologyRepository().getPropertyByIRI(PUBLIC_PROPERTY_IRI, null);
        assertEquals(PUBLIC_PROPERTY_IRI, noWorkspace.getIri());

        OntologyProperty withWorkspace = getOntologyRepository().getPropertyByIRI(PUBLIC_PROPERTY_IRI, workspaceId);
        assertEquals(PUBLIC_PROPERTY_IRI, withWorkspace.getIri());
    }

    @Test
    public void testCreatingPublicPropertyAsSystem() {
        List<Concept> thing = Collections.singletonList(getOntologyRepository().getEntityConcept(workspaceId));
        OntologyPropertyDefinition ontologyPropertyDefinition = new OntologyPropertyDefinition(thing, PUBLIC_PROPERTY_IRI, SANDBOX_DISPLAY_NAME, PropertyType.DATE);
        getOntologyRepository().getOrCreateProperty(ontologyPropertyDefinition, systemUser, null);
        getOntologyRepository().clearCache();

        OntologyProperty noWorkspace = getOntologyRepository().getPropertyByIRI(PUBLIC_PROPERTY_IRI, null);
        assertEquals(PUBLIC_PROPERTY_IRI, noWorkspace.getIri());

        OntologyProperty withWorkspace = getOntologyRepository().getPropertyByIRI(PUBLIC_PROPERTY_IRI, workspaceId);
        assertEquals(PUBLIC_PROPERTY_IRI, withWorkspace.getIri());
    }

    @Test(expected = VisalloAccessDeniedException.class)
    public void testCreatingSandboxedPropertyWithoutAddPermissionPrivilege() {
        List<Concept> thing = Collections.singletonList(getOntologyRepository().getEntityConcept(workspaceId));
        OntologyPropertyDefinition ontologyPropertyDefinition = new OntologyPropertyDefinition(thing, SANDBOX_PROPERTY_IRI, SANDBOX_DISPLAY_NAME, PropertyType.DATE);
        getOntologyRepository().getOrCreateProperty(ontologyPropertyDefinition, user, workspaceId);
    }

    @Test
    public void testCreatingSandboxedProperty() {
        setPrivileges(user, Collections.singleton(Privilege.ONTOLOGY_ADD));

        List<Concept> thing = Collections.singletonList(getOntologyRepository().getEntityConcept(workspaceId));
        OntologyPropertyDefinition ontologyPropertyDefinition = new OntologyPropertyDefinition(thing, SANDBOX_PROPERTY_IRI, SANDBOX_DISPLAY_NAME, PropertyType.DATE);
        getOntologyRepository().getOrCreateProperty(ontologyPropertyDefinition, user, workspaceId);
        getOntologyRepository().clearCache();

        OntologyProperty noWorkspace = getOntologyRepository().getPropertyByIRI(SANDBOX_PROPERTY_IRI, null);
        assertNull(noWorkspace);

        OntologyProperty withWorkspace = getOntologyRepository().getPropertyByIRI(SANDBOX_PROPERTY_IRI, workspaceId);
        assertEquals(SANDBOX_PROPERTY_IRI, withWorkspace.getIri());
    }

    @Test(expected = VisalloAccessDeniedException.class)
    public void testPublishingPropertyWithoutPublishPrivilege() {
        setPrivileges(user, Collections.singleton(Privilege.ONTOLOGY_ADD));

        List<Concept> thing = Collections.singletonList(getOntologyRepository().getEntityConcept(workspaceId));
        OntologyPropertyDefinition ontologyPropertyDefinition = new OntologyPropertyDefinition(thing, SANDBOX_PROPERTY_IRI, SANDBOX_DISPLAY_NAME, PropertyType.DATE);
        OntologyProperty sandboxedProperty = getOntologyRepository().getOrCreateProperty(ontologyPropertyDefinition, user, workspaceId);
        getOntologyRepository().publishProperty(sandboxedProperty, user, workspaceId);
    }

    @Test
    public void testPublishingProperty() {
        setPrivileges(user, Sets.newHashSet(Privilege.ONTOLOGY_ADD, Privilege.ONTOLOGY_PUBLISH));

        List<Concept> thing = Collections.singletonList(getOntologyRepository().getEntityConcept(workspaceId));
        OntologyPropertyDefinition ontologyPropertyDefinition = new OntologyPropertyDefinition(thing, SANDBOX_PROPERTY_IRI, SANDBOX_DISPLAY_NAME, PropertyType.DATE);
        OntologyProperty sandboxedProperty = getOntologyRepository().getOrCreateProperty(ontologyPropertyDefinition, user, workspaceId);
        getOntologyRepository().publishProperty(sandboxedProperty, user, workspaceId);
        getOntologyRepository().clearCache();

        OntologyProperty publicProperty = getOntologyRepository().getPropertyByIRI(SANDBOX_PROPERTY_IRI, null);
        assertEquals(SANDBOX_PROPERTY_IRI, publicProperty.getIri());
    }

    private void createSampleOntology() {
        setPrivileges(user, Sets.newHashSet(Privilege.ONTOLOGY_ADD, Privilege.ONTOLOGY_PUBLISH));

        getWorkspaceRepository().add(workspaceId, "Junit Workspace", user);

        Concept thing = getOntologyRepository().getEntityConcept(workspaceId);
        getOntologyRepository().getOrCreateConcept(thing, PUBLIC_CONCEPT_IRI, PUBLIC_DISPLAY_NAME, null, systemUser, null);
        getOntologyRepository().getOrCreateConcept(thing, SANDBOX_CONCEPT_IRI, SANDBOX_DISPLAY_NAME, null, user, workspaceId);

        List<Concept> things = Collections.singletonList(thing);
        Relationship publicRelationship = getOntologyRepository().getOrCreateRelationshipType(null, things, things, PUBLIC_RELATIONSHIP_IRI, true, systemUser, null);
        getOntologyRepository().getOrCreateRelationshipType(publicRelationship, things, things, SANDBOX_RELATIONSHIP_IRI, true, user, workspaceId);

        OntologyPropertyDefinition publicPropertyDefinition = new OntologyPropertyDefinition(things, PUBLIC_PROPERTY_IRI, PUBLIC_DISPLAY_NAME, PropertyType.STRING);
        publicPropertyDefinition.setTextIndexHints(Collections.singleton(TextIndexHint.EXACT_MATCH));
        publicPropertyDefinition.setUserVisible(true);
        getOntologyRepository().getOrCreateProperty(publicPropertyDefinition, systemUser, null);

        OntologyPropertyDefinition sandboxPropertyDefinition = new OntologyPropertyDefinition(things, SANDBOX_PROPERTY_IRI, SANDBOX_DISPLAY_NAME, PropertyType.STRING);
        sandboxPropertyDefinition.setTextIndexHints(Collections.singleton(TextIndexHint.EXACT_MATCH));
        sandboxPropertyDefinition.setUserVisible(true);
        getOntologyRepository().getOrCreateProperty(sandboxPropertyDefinition, user, workspaceId);

        getOntologyRepository().clearCache();
    }

    private void validateTestOwlRelationship() {
        Relationship relationship = getOntologyRepository().getRelationshipByIRI(TEST_IRI + "#personKnowsPerson", null);
        assertEquals("Knows", relationship.getDisplayName());
        assertEquals("prop('http://visallo.org/test#firstMet') || ''", relationship.getTimeFormula());
        assertTrue(relationship.getRangeConceptIRIs().contains("http://visallo.org/test#person"));
        assertTrue(relationship.getDomainConceptIRIs().contains("http://visallo.org/test#person"));

        relationship = getOntologyRepository().getRelationshipByIRI(TEST_IRI + "#personIsRelatedToPerson", null);
        assertEquals("Is Related To", relationship.getDisplayName());
        String[] intents = relationship.getIntents();
        assertEquals(1, intents.length);
        assertEquals("test", intents[0]);
        assertTrue(relationship.getRangeConceptIRIs().contains("http://visallo.org/test#person"));
        assertTrue(relationship.getDomainConceptIRIs().contains("http://visallo.org/test#person"));
    }

    private void validateTestOwlProperties() {
        OntologyProperty nameProperty = getOntologyRepository().getPropertyByIRI(TEST_IRI + "#name", null);
        assertEquals("Name", nameProperty.getDisplayName());
        assertEquals(PropertyType.STRING, nameProperty.getDataType());
        assertEquals("_.compact([\n" +
                "            dependentProp('http://visallo.org/test#firstName'),\n" +
                "            dependentProp('http://visallo.org/test#middleName'),\n" +
                "            dependentProp('http://visallo.org/test#lastName')\n" +
                "            ]).join(', ')", nameProperty.getDisplayFormula().trim());
        ImmutableList<String> dependentPropertyIris = nameProperty.getDependentPropertyIris();
        assertEquals(3, dependentPropertyIris.size());
        assertTrue(dependentPropertyIris.contains("http://visallo.org/test#firstName"));
        assertTrue(dependentPropertyIris.contains("http://visallo.org/test#middleName"));
        assertTrue(dependentPropertyIris.contains("http://visallo.org/test#lastName"));
        List<String> intents = Arrays.asList(nameProperty.getIntents());
        assertEquals(1, intents.size());
        assertTrue(intents.contains("test3"));
        assertEquals(
                "dependentProp('http://visallo.org/test#lastName') && dependentProp('http://visallo.org/test#firstName')",
                nameProperty.getValidationFormula()
        );
        assertEquals("Personal Information", nameProperty.getPropertyGroup());
        assertEquals("test", nameProperty.getDisplayType());
        assertFalse(nameProperty.getAddable());
        assertFalse(nameProperty.getUpdateable());
        assertFalse(nameProperty.getDeleteable());
        Map<String, String> possibleValues = nameProperty.getPossibleValues();
        assertEquals(2, possibleValues.size());
        assertEquals("test 1", possibleValues.get("T1"));
        assertEquals("test 2", possibleValues.get("T2"));

        Concept person = getOntologyRepository().getConceptByIRI(TEST_IRI + "#person", null);
        assertTrue(person.getProperties()
                .stream()
                .anyMatch(p -> p.getIri().equals(nameProperty.getIri()))
        );

        OntologyProperty firstMetProperty = getOntologyRepository().getPropertyByIRI(TEST_IRI + "#firstMet", null);
        assertEquals("First Met", firstMetProperty.getDisplayName());
        assertEquals(PropertyType.DATE, firstMetProperty.getDataType());

        OntologyProperty favColorProperty = getOntologyRepository().getPropertyByIRI(TEST_IRI + "#favoriteColor");
        assertEquals("Favorite Color", favColorProperty.getDisplayName());
        possibleValues = favColorProperty.getPossibleValues();
        assertEquals(2, possibleValues.size());
        assertEquals("red 1", possibleValues.get("Red"));
        assertEquals("blue 2", possibleValues.get("Blue"));

        Relationship relationship = getOntologyRepository().getRelationshipByIRI(TEST_IRI + "#personKnowsPerson", null);
        assertTrue(relationship.getProperties()
                .stream()
                .anyMatch(p -> p.getIri().equals(firstMetProperty.getIri()))
        );
    }

    private void validateTestOwlConcepts(int expectedIriSize) throws IOException {
        Concept contact = getOntologyRepository().getConceptByIRI(TEST_IRI + "#contact", null);
        assertEquals("Contact", contact.getDisplayName());
        assertEquals("rgb(149, 138, 218)", contact.getColor());
        assertEquals("test", contact.getDisplayType());
        List<String> intents = Arrays.asList(contact.getIntents());
        assertEquals(1, intents.size());
        assertTrue(intents.contains("face"));

        Concept person = getOntologyRepository().getConceptByIRI(TEST_IRI + "#person", null);
        assertEquals("Person", person.getDisplayName());
        intents = Arrays.asList(person.getIntents());
        assertEquals(1, intents.size());
        assertTrue(intents.contains("person"));
        assertEquals("prop('http://visallo.org/test#birthDate') || ''", person.getTimeFormula());
        assertEquals("prop('http://visallo.org/test#name') || ''", person.getTitleFormula());

        byte[] bytes = IOUtils.toByteArray(OntologyRepositoryTestBase.class.getResourceAsStream("glyphicons_003_user@2x.png"));
        assertArrayEquals(bytes, person.getGlyphIcon());
        assertEquals("rgb(28, 137, 28)", person.getColor());

        Set<Concept> conceptAndAllChildren = getOntologyRepository().getConceptAndAllChildren(contact, null);
        List<String> iris = Lists.newArrayList();
        conceptAndAllChildren.forEach(c -> iris.add(c.getIRI()));
        assertEquals(expectedIriSize, iris.size());
        assertTrue(iris.contains(contact.getIRI()));
        assertTrue(iris.contains(person.getIRI()));
    }

    private void validateChangedOwlRelationships() throws IOException {
        Relationship relationship = getOntologyRepository().getRelationshipByIRI(TEST_IRI + "#personKnowsPerson", null);
        assertEquals("Person Knows Person", relationship.getDisplayName());
        assertNull(relationship.getTimeFormula());
        assertFalse(relationship.getRangeConceptIRIs().contains("http://visallo.org/test#person2"));
        assertTrue(relationship.getRangeConceptIRIs().contains("http://visallo.org/test#person"));
        assertTrue(relationship.getDomainConceptIRIs().contains("http://visallo.org/test#person"));
    }

    private void validateChangedOwlConcepts() throws IOException {
        Concept contact = getOntologyRepository().getConceptByIRI(TEST_IRI + "#contact", null);
        Concept person = getOntologyRepository().getConceptByIRI(TEST_IRI + "#person", null);
        assertEquals("Person", person.getDisplayName());
        List<String> intents = Arrays.asList(person.getIntents());
        assertEquals(1, intents.size());
        assertFalse(intents.contains("person"));
        assertFalse(intents.contains("face"));
        assertTrue(intents.contains("test"));
        assertNull(person.getTimeFormula());
        assertEquals("prop('http://visallo.org/test#name') || ''", person.getTitleFormula());

        assertNull(person.getGlyphIcon());
        assertEquals("rgb(28, 137, 28)", person.getColor());

        Set<Concept> conceptAndAllChildren = getOntologyRepository().getConceptAndAllChildren(contact, null);
        List<String> iris = Lists.newArrayList();
        conceptAndAllChildren.forEach(c -> iris.add(c.getIRI()));
        assertEquals(2, iris.size());
        assertTrue(iris.contains(contact.getIRI()));
        assertTrue(iris.contains(person.getIRI()));
    }

    private void validateChangedOwlProperties() throws IOException {
        OntologyProperty nameProperty = getOntologyRepository().getPropertyByIRI(TEST_IRI + "#name", null);
        assertEquals("http://visallo.org/test#name", nameProperty.getDisplayName());
        assertEquals(PropertyType.STRING, nameProperty.getDataType());
        assertEquals("_.compact([\n" +
                "            dependentProp('http://visallo.org/test#firstName'),\n" +
                "            dependentProp('http://visallo.org/test#lastName')\n" +
                "            ]).join(', ')", nameProperty.getDisplayFormula().trim());
        ImmutableList<String> dependentPropertyIris = nameProperty.getDependentPropertyIris();
        assertEquals(3, dependentPropertyIris.size());
        assertTrue(dependentPropertyIris.contains("http://visallo.org/test#firstName"));
        assertTrue(dependentPropertyIris.contains("http://visallo.org/test#middleName"));
        assertTrue(dependentPropertyIris.contains("http://visallo.org/test#lastName"));
        List<String> intents = Arrays.asList(nameProperty.getIntents());
        assertEquals(1, intents.size());
        assertTrue(intents.contains("test3"));
        assertEquals(
                "dependentProp('http://visallo.org/test#lastName') && dependentProp('http://visallo.org/test#firstName')",
                nameProperty.getValidationFormula()
        );
        assertEquals("Personal Information", nameProperty.getPropertyGroup());
        assertEquals("test 2", nameProperty.getDisplayType());
        assertTrue(nameProperty.getAddable());
        assertTrue(nameProperty.getUpdateable());
        assertTrue(nameProperty.getDeleteable());
        Map<String, String> possibleValues = nameProperty.getPossibleValues();
        assertNull(possibleValues);

        OntologyProperty firstMetProperty = getOntologyRepository().getPropertyByIRI(TEST_IRI + "#firstMet", null);
        assertEquals("First Met", firstMetProperty.getDisplayName());
        assertEquals(PropertyType.DATE, firstMetProperty.getDataType());

        OntologyProperty favColorProperty = getOntologyRepository().getPropertyByIRI(TEST_IRI + "#favoriteColor");
        assertEquals("Favorite Color", favColorProperty.getDisplayName());
        possibleValues = favColorProperty.getPossibleValues();
        assertEquals(2, possibleValues.size());
        assertEquals("red 1", possibleValues.get("Red"));
        assertEquals("blue 2", possibleValues.get("Blue"));

        Relationship relationship = getOntologyRepository().getRelationshipByIRI(TEST_IRI + "#personKnowsPerson", null);
        assertTrue(relationship.getProperties()
                .stream()
                .anyMatch(p -> p.getIri().equals(firstMetProperty.getIri()))
        );
    }

    private void loadTestOwlFile() throws Exception {
        createTestOntologyRepository(TEST_OWL, TEST_IRI);
        validateTestOwlRelationship();
        validateTestOwlConcepts(2);
        validateTestOwlProperties();
    }


    private void loadHierarchyOwlFile() throws Exception {
        createTestOntologyRepository(TEST_HIERARCHY_OWL, TEST_HIERARCHY_IRI);
    }

    private void createTestOntologyRepository(String owlFileResourcePath, String iri) throws Exception {
        File testOwl = new File(OntologyRepositoryTestBase.class.getResource(owlFileResourcePath).toURI());
        getOntologyRepository().importFile(testOwl, IRI.create(iri), authorizations);
    }

    @Override
    protected abstract OntologyRepository getOntologyRepository();
}
