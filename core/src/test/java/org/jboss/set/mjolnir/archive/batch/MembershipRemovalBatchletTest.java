package org.jboss.set.mjolnir.archive.batch;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import org.apache.deltaspike.testcontrol.api.junit.CdiTestRunner;
import org.assertj.core.groups.Tuple;
import org.eclipse.egit.github.core.Repository;
import org.jboss.set.mjolnir.archive.ArchivingBean;
import org.jboss.set.mjolnir.archive.domain.GitHubOrganization;
import org.jboss.set.mjolnir.archive.domain.RemovalStatus;
import org.jboss.set.mjolnir.archive.domain.UserRemoval;
import org.jboss.set.mjolnir.archive.util.TestUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.TypedQuery;
import java.sql.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@RunWith(CdiTestRunner.class)
public class MembershipRemovalBatchletTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(8089);

    @Inject
    private EntityManager em;

    @Inject
    private MembershipRemovalBatchlet batchlet;

    @Inject
    private ArchivingBean archivingBean;

    @Before
    public void setup() {
        // create two sample removals

        em.getTransaction().begin();

        UserRemoval userRemoval = new UserRemoval();
        userRemoval.setUsername("thofman");
        em.persist(userRemoval);

        userRemoval = new UserRemoval();
        userRemoval.setUsername("lvydra");
        userRemoval.setRemoveOn(new Date(System.currentTimeMillis() - (24 * 3600 * 1000))); // removal date 1 day in past
        em.persist(userRemoval);

        userRemoval = new UserRemoval();
        userRemoval.setUsername("future");
        userRemoval.setRemoveOn(new Date(System.currentTimeMillis() + (24 * 3600 * 1000))); // removal date 1 day in future
        em.persist(userRemoval);

        em.getTransaction().commit();
    }

    @Test
    public void testRemovalsMarked() {
        // verify there are two fresh removals present in the database
        TypedQuery<UserRemoval> findRemovalsQuery = em.createNamedQuery(UserRemoval.FIND_REMOVALS_TO_PROCESS, UserRemoval.class);
        List<UserRemoval> removals = findRemovalsQuery.getResultList();
        assertThat(removals)
                .extracting("username")
                .containsOnly("thofman", "lvydra");
        assertThat(removals.size()).isEqualTo(2);

        // let the batchlet load the removals
        batchlet.loadRemovalsToProcess();

        // verify removals are not available anymore
        removals = findRemovalsQuery.getResultList();
        assertThat(removals).isEmpty();
    }

    @Test
    public void testLoadOrganizations() {
        List<GitHubOrganization> orgs = batchlet.loadOrganizations();
        assertThat(orgs.size()).isEqualTo(1);
        assertThat(orgs.get(0).getName()).isEqualTo("testorg");
        assertThat(orgs.get(0).getTeams())
                .extracting("name")
                .containsOnly("Test Team", "Other Team");
    }

    @Test
    public void testFindGitHubUsername() {
        String ghUsername = batchlet.findGitHubUsername("thofman");
        assertThat(ghUsername).isEqualTo("TomasHofman");
    }

    @Test
    public void testBatchlet() throws Exception {
        TestUtils.setupGitHubApiStubs();

        String result = batchlet.process();
        em.clear();
        assertThat(result).isEqualTo("DONE");

        // verify that all removals has been marked as processed
        TypedQuery<UserRemoval> findRemovalsQuery = em.createNamedQuery(UserRemoval.FIND_REMOVALS_TO_PROCESS, UserRemoval.class);
        List<UserRemoval> removalsToProcess = findRemovalsQuery.getResultList();
        assertThat(removalsToProcess.size()).isEqualTo(0);

        // verify processed removal state
        List<UserRemoval> removals = em.createQuery("SELECT r FROM UserRemoval r where r.username = :username", UserRemoval.class)
                .setParameter("username", "thofman")
                .getResultList();
        assertThat(removals.size()).isEqualTo(1);
        assertThat(removals.get(0)).satisfies(removal -> {
            em.refresh(removal);
            assertThat(removal.getStatus()).isEqualTo(RemovalStatus.COMPLETED);
            assertThat(removal.getStarted()).isNotNull();
            assertThat(removal.getCompleted()).isNotNull();

            // verify that repository forks has been saved
            assertThat(removal.getForks())
                    .extracting("sourceRepositoryName", "repositoryName")
                    .containsOnly(
                            Tuple.tuple("testorg/aphrodite", "TomasHofman/aphrodite"),
                            Tuple.tuple("testorg/activemq-artemis", "TomasHofman/activemq-artemis")
                    );
        });

        // verify unprocessed removal state
        removals = em.createQuery("SELECT r FROM UserRemoval r where r.username = :username", UserRemoval.class)
                .setParameter("username", "lvydra")
                .getResultList();
        assertThat(removals.size()).isEqualTo(1);
        assertThat(removals.get(0)).satisfies(removal -> {
            em.refresh(removal);
            assertThat(removal.getStatus()).isEqualTo(RemovalStatus.UNKNOWN_USER);
            assertThat(removal.getLogs())
                    .extracting("message")
                    .contains("Ignoring removal request for user lvydra");
        });

        // verify that repositories were archived (ArchivingBean is mocked)
        ArgumentCaptor<Repository> repositoryArgumentCaptor = ArgumentCaptor.forClass(Repository.class);
        verify(archivingBean, times(2))
                .createRepositoryMirror(repositoryArgumentCaptor.capture());
        assertThat(repositoryArgumentCaptor.getAllValues())
                .extracting("cloneUrl")
                .containsOnly("https://github.com/TomasHofman/aphrodite.git",
                        "https://github.com/TomasHofman/activemq-artemis.git");

        // TODO: verify that user was removed from GitHub organization teams
    }

}
