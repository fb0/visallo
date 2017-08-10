package org.visallo.core.model.ontology;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class Ontology {
    private final String workspaceId;
    private final Map<String, Concept> conceptsByIri;
    private final Map<String, Relationship> relationshipsByIri;
    private final Map<String, OntologyProperty> propertiesByIri;

    public Ontology(Iterable<Concept> concepts, Iterable<Relationship> relationships, String workspaceId) {
        this.workspaceId = workspaceId;

        Map<String, OntologyProperty> propertyMap = new HashMap<>();

        conceptsByIri = Collections.unmodifiableMap(StreamSupport.stream(concepts.spliterator(), false)
                .collect(Collectors.toMap(Concept::getIRI, concept -> {
                    Collection<OntologyProperty> properties = concept.getProperties();
                    if (properties != null && properties.size() > 0) {
                        properties.forEach(property -> propertyMap.put(property.getIri(), property));
                    }
                    return concept;
                })));
        relationshipsByIri = Collections.unmodifiableMap(StreamSupport.stream(relationships.spliterator(), false)
                .collect(Collectors.toMap(Relationship::getIRI, relationship -> {
                    Collection<OntologyProperty> properties = relationship.getProperties();
                    if (properties != null && properties.size() > 0) {
                        properties.forEach(property -> propertyMap.put(property.getIri(), property));
                    }
                    return relationship;
                })));

        propertiesByIri = Collections.unmodifiableMap(propertyMap);
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public Collection<Concept> getConcepts() {
        return conceptsByIri.values();
    }

    public Map<String, Concept> getConceptsByIri() {
        return conceptsByIri;
    }

    public Concept getConceptByIri(String iri) {
        return conceptsByIri.get(iri);
    }

    public Collection<Relationship> getRelationships() {
        return relationshipsByIri.values();
    }

    public Map<String, Relationship> getRelationshipsByIri() {
        return relationshipsByIri;
    }

    public Relationship getRelationshipByIri(String iri) {
        return relationshipsByIri.get(iri);
    }

    public Collection<OntologyProperty> getProperties() {
        return propertiesByIri.values();
    }

    public Map<String, OntologyProperty> getPropertiesByIri() {
        return propertiesByIri;
    }

    public OntologyProperty getPropertyByIri(String iri) {
        return propertiesByIri.get(iri);
    }
}
