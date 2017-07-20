package org.visallo.email;

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
import static org.visallo.email.EmailGraphPropertyWorker.EMAIL_CONCEPT_INTENT;

@RunWith(MockitoJUnitRunner.class)
public class EmailGraphPropertyWorkerTest extends TermMentionGraphPropertyWorkerTestBase {
    private static final String EMAIL_TEXT = "This person's email is person.one@visallo.com, and his best buddy's email @ vertexium.org is person.two@vertexium.org";
    private static final String EMAIL_NEW_LINES = "This person's email is \nperson.one@visallo.com, and his best buddy's \nemail is person.two@vertexium.org\n";
    private static final String EMAIL_MISSING = "This is a sentence without any emails in it.";

    @Before
    public void setup() {
        InMemoryConcept concept = new InMemoryConcept(CONCEPT_IRI, VisalloProperties.CONCEPT_TYPE_THING, null);
        when(ontologyRepository.getRequiredConceptByIntent(EMAIL_CONCEPT_INTENT, PUBLIC)).thenReturn(concept);
    }

    @Override
    public GraphPropertyWorker getGpw() throws Exception {
        EmailGraphPropertyWorker gpw = new EmailGraphPropertyWorker();
        prepare(gpw);
        return gpw;
    }

    @Test
    public void testEmailExtraction() throws Exception {
        doExtractionTest(EMAIL_TEXT, Arrays.asList(
                new ExpectedTermMention("person.one@visallo.com", 23L, 45L),
                new ExpectedTermMention("person.two@vertexium.org", 93L, 117L)
        ));
    }

    @Test
    public void testEmailExtractionWithNewlines() throws Exception {
        doExtractionTest(EMAIL_NEW_LINES, Arrays.asList(
                new ExpectedTermMention("person.one@visallo.com", 24L, 46L),
                new ExpectedTermMention("person.two@vertexium.org", 79L, 103L)
        ));
    }

    @Test
    public void testNegativeEmailExtraction() throws Exception {
        doExtractionTest(EMAIL_MISSING, null);
    }
}
