package org.visallo.zipcode;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;
import org.visallo.core.ingest.graphProperty.GraphPropertyWorker;
import org.visallo.core.ingest.graphProperty.TermMentionGraphPropertyWorkerTestBase;
import org.visallo.core.model.properties.VisalloProperties;
import org.visallo.vertexium.model.ontology.InMemoryConcept;

import java.util.Arrays;

import static org.mockito.Mockito.when;
import static org.visallo.core.model.ontology.OntologyRepository.PUBLIC;
import static org.visallo.zipcode.ZipCodeGraphPropertyWorker.ZIPCODE_CONCEPT_INTENT;

@RunWith(MockitoJUnitRunner.class)
public class ZipCodeGraphPropertyWorkerTest extends TermMentionGraphPropertyWorkerTestBase {
    private static final String ZIPCODE_TEXT = "There are more than 2016 people that live in the zip code 20165 and more than 20191-16 that live in 20191-1234";
    private static final String ZIPCODE_NEW_LINES = "There are more than 2016 people that live in the zip code \n20165 and more than 20191-16 that live \nin 20191-1234\n";
    private static final String ZIPCODE_MISSING = "This is a sentence without any zip codes in it.";

    @Before
    public void setup() {
        InMemoryConcept concept = new InMemoryConcept(CONCEPT_IRI, VisalloProperties.CONCEPT_TYPE_THING, null);
        when(ontologyRepository.getRequiredConceptByIntent(ZIPCODE_CONCEPT_INTENT, PUBLIC)).thenReturn(concept);
    }

    @Override
    public GraphPropertyWorker getGpw() throws Exception {
        ZipCodeGraphPropertyWorker gpw = new ZipCodeGraphPropertyWorker();
        prepare(gpw);
        return gpw;
    }

    @Test
    public void testZipCodeExtraction() throws Exception {
        doExtractionTest(ZIPCODE_TEXT, Arrays.asList(
                new ExpectedTermMention("20165", 58L, 63L),
                new ExpectedTermMention("20191", 78L, 83L),
                new ExpectedTermMention("20191-1234", 100L, 110L)
        ));
    }

    @Test
    public void testZipCodeExtractionWithNewlines() throws Exception {
        doExtractionTest(ZIPCODE_NEW_LINES, Arrays.asList(
                new ExpectedTermMention("20165", 59L, 64L),
                new ExpectedTermMention("20191", 79L, 84L),
                new ExpectedTermMention("20191-1234", 102L, 112L)
        ));
    }

    @Test
    public void testNegativeZipCodeExtraction() throws Exception {
        doExtractionTest(ZIPCODE_MISSING, null);
    }
}
