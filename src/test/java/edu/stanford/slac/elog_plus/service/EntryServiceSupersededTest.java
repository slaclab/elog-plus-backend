package edu.stanford.slac.elog_plus.service;

import edu.stanford.slac.ad.eed.baselib.exception.ControllerLogicException;
import edu.stanford.slac.elog_plus.api.v1.dto.EntryNewDTO;
import edu.stanford.slac.elog_plus.api.v1.dto.LogbookDTO;
import edu.stanford.slac.elog_plus.api.v1.dto.NewLogbookDTO;
import edu.stanford.slac.elog_plus.api.v1.dto.QueryWithAnchorDTO;
import edu.stanford.slac.elog_plus.model.Entry;
import edu.stanford.slac.elog_plus.model.Logbook;
import edu.stanford.slac.elog_plus.repository.EntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

@AutoConfigureMockMvc
@SpringBootTest()
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(MockitoExtension.class)
@ActiveProfiles({"test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class EntryServiceSupersededTest {
    @SpyBean
    private EntryRepository entryRepository;
    @Autowired
    private EntryService entryService;
    @Autowired
    private LogbookService logbookService;
    @Autowired
    private SharedUtilityService sharedUtilityService;
    @Autowired
    MongoTemplate mongoTemplate;

    @BeforeEach
    public void preTest() {
        mongoTemplate.remove(new Query(), Entry.class);
        mongoTemplate.remove(new Query(), Logbook.class);
    }

    private LogbookDTO getTestLogbook() {
        String logbookId =
                assertDoesNotThrow(
                        () -> logbookService.createNew(
                                NewLogbookDTO
                                        .builder()
                                        .name(UUID.randomUUID().toString())
                                        .build()
                        )
                );
        return assertDoesNotThrow(
                () -> logbookService.getLogbook(logbookId)
        );
    }

    @Test
    public void fixFindAllAfterSupersededOpsAFollowedUpEntry() {
        // create the logbook
        var logbook = getTestLogbook();
        // create entry
        String newLogID = entryService.createNew(
                EntryNewDTO
                        .builder()
                        .logbooks(Set.of(logbook.id()))
                        .text("This is a log for test")
                        .title("A very wonderful log")
                        .build(),
                sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
        );

        assertThat(newLogID).isNotNull();

        // create a new followup to the entry
        entryService.createNewFollowUp(
                newLogID,
                EntryNewDTO
                        .builder()
                        .logbooks(Set.of(logbook.id()))
                        .text("This is a followup log for log %s".formatted(newLogID))
                        .title("A very wonderful followup log")
                        .build(),
                sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
        );

        // create another follow up
        entryService.createNewFollowUp(
                newLogID,
                EntryNewDTO
                        .builder()
                        .logbooks(Set.of(logbook.id()))
                        .text("This is another followup log for log %s".formatted(newLogID))
                        .title("A very wonderful followup log")
                        .build(),
                sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
        );
        // now superseed the original entry
        entryService.createNewSupersede(
                newLogID,
                EntryNewDTO
                        .builder()
                        .logbooks(Set.of(logbook.id()))
                        .text("This is a superseded for log for log %s".formatted(newLogID))
                        .title("A very wonderful superseded log")
                        .build(),
                sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
        );

        // this now shouldn't gives error
        var result = assertDoesNotThrow(
                () -> entryService.findAll(
                        QueryWithAnchorDTO
                                .builder()
                                .limit(20)
                                .logbooks(emptyList())
                                .build()
                )
        );
        assertThat(result).hasSize(3);
    }

    @Test
    void updatedReferenceAfterSupersede() {
        // create the logbook
        var logbook = getTestLogbook();
        // create entry
        String newLogID = assertDoesNotThrow(
                () -> entryService.createNew(
                        EntryNewDTO
                                .builder()
                                .logbooks(Set.of(logbook.id()))
                                .title("Log to be referenced")
                                .text("This is a log for test")
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );

        assertThat(newLogID).isNotNull();

        // create a new entry that reference the one above
        String referenceEntry1 = assertDoesNotThrow(
                () -> entryService.createNew(
                        EntryNewDTO
                                .builder()
                                .logbooks(Set.of(logbook.id()))
                                .title("Title Reference %s".formatted(newLogID))
                                .text("Reference 1 to %s".formatted(sharedUtilityService.createReferenceHtmlFragment("This is a text with reference", List.of(newLogID))))
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );

        // create another entry that refer to the same entry with newLogID
        String referenceEntry2 = assertDoesNotThrow(
                () -> entryService.createNewFollowUp(
                        newLogID,
                        EntryNewDTO
                                .builder()
                                .logbooks(Set.of(logbook.id()))
                                .title("Title reference 2 to %s".formatted(newLogID))
                                .text("Reference 2 %s".formatted(sharedUtilityService.createReferenceHtmlFragment("This is a text with reference", List.of(newLogID))))
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );

        // now superseded the original entry
        String newSupersedeEntryId = assertDoesNotThrow(
                () -> entryService.createNewSupersede(
                        newLogID,
                        EntryNewDTO
                                .builder()
                                .logbooks(Set.of(logbook.id()))
                                .text("This is a superseded for log for log %s".formatted(newLogID))
                                .title("A very wonderful superseded log")
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );

        //now the entries with id referenceEntry1 and referenceEntry2 should have the reference updated
        var entry1 = assertDoesNotThrow(
                () -> entryService.getFullEntry(
                        referenceEntry1,
                        Optional.of(false),
                        Optional.of(false),
                        Optional.of(false),
                        Optional.of(true),//include references
                        Optional.of(false),//include referencedBy
                        Optional.of(false)//include includeSuperseded
                )
        );
        assertThat(entry1).isNotNull();
        assertThat(entry1.references()).hasSize(1);
        assertThat(entry1.references().getFirst().id()).isEqualTo(newSupersedeEntryId);
        assertThat(sharedUtilityService.htmlContainsReferenceWithId(entry1.text(), newSupersedeEntryId)).isTrue();

        var entry2 = assertDoesNotThrow(
                () -> entryService.getFullEntry(
                        referenceEntry2,
                        Optional.of(false),
                        Optional.of(false),
                        Optional.of(false),
                        Optional.of(true),//include references
                        Optional.of(false),//include referencedBy
                        Optional.of(false)//include includeSuperseded
                )
        );
        assertThat(entry2).isNotNull();
        assertThat(entry2.references()).hasSize(1);
        assertThat(entry2.references().getFirst().id()).isEqualTo(newSupersedeEntryId);
        assertThat(sharedUtilityService.htmlContainsReferenceWithId(entry2.text(), newSupersedeEntryId)).isTrue();

        // get the history of the superseded entry
        var supersedeEntry = assertDoesNotThrow(
                () -> entryService.getFullEntry(
                        newSupersedeEntryId,
                        Optional.of(false),
                        Optional.of(false),
                        Optional.of(true), //include history
                        Optional.of(false),//include references
                        Optional.of(false),//include referencedBy
                        Optional.of(false)//include includeSuperseded
                )
        );
        assertThat(supersedeEntry).isNotNull();
        assertThat(supersedeEntry.history()).hasSize(1);
        assertThat(supersedeEntry.history().getFirst().id()).isEqualTo(newLogID);
    }

    @Test
    void updatedReferenceFailsAfterDBConnectionErrorNoIsChanged() {
        // create the logbook
        var logbook = getTestLogbook();
        // create entry
        String newLogID = assertDoesNotThrow(
                () -> entryService.createNew(
                        EntryNewDTO
                                .builder()
                                .logbooks(Set.of(logbook.id()))
                                .title("Log to be referenced")
                                .text("This is a log for test")
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );

        assertThat(newLogID).isNotNull();

        // create a new entry that reference the one above
        String referenceEntry1 = assertDoesNotThrow(
                () -> entryService.createNew(
                        EntryNewDTO
                                .builder()
                                .logbooks(Set.of(logbook.id()))
                                .title("Title Reference %s".formatted(newLogID))
                                .text("Reference 1 to %s".formatted(sharedUtilityService.createReferenceHtmlFragment("This is a text with reference", List.of(newLogID))))
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );

        // create another entry that refer to the same entry with newLogID
        String referenceEntry2 = assertDoesNotThrow(
                () -> entryService.createNewFollowUp(
                        newLogID,
                        EntryNewDTO
                                .builder()
                                .logbooks(Set.of(logbook.id()))
                                .title("Title reference 2 to %s".formatted(newLogID))
                                .text("Reference 2 %s".formatted(sharedUtilityService.createReferenceHtmlFragment("This is a text with reference", List.of(newLogID))))
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );

        // now simulate the fail of the db at this point

        // Temporarily mock the createNewSupersede method to throw an exception
        doAnswer(invocation -> {
            if(invocation.getArgument(0, Entry.class).getId().compareToIgnoreCase(newLogID) == 0) {
                throw ControllerLogicException.builder().build();
            }
            return invocation.callRealMethod();
        }).when(entryRepository).save(any(Entry.class));

        var failsExceptionDueDBError = assertThrows(
                ControllerLogicException.class,
                () -> entryService.createNewSupersede(
                        newLogID,
                        EntryNewDTO
                                .builder()
                                .logbooks(Set.of(logbook.id()))
                                .text("This is a superseded for log for log %s".formatted(newLogID))
                                .title("A very wonderful superseded log")
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );
        assertThat(failsExceptionDueDBError).isNotNull();

        // check for original entry not superseded
        var originalEntry = assertDoesNotThrow(
                () -> entryService.getFullEntry(
                        newLogID,
                        Optional.of(false),
                        Optional.of(false),
                        Optional.of(false),
                        Optional.of(true),//include references
                        Optional.of(false),//include referencedBy
                        Optional.of(true)//include includeSuperseded
                )
        );
        assertThat(originalEntry).isNotNull();
        // supersede operation has failed, so the original entry should not have been superseded
        assertThat(originalEntry.supersededBy()).isNull();

        //now the entries with id referenceEntry1 and referenceEntry2 should not have been updated
        var entry1 = assertDoesNotThrow(
                () -> entryService.getFullEntry(
                        referenceEntry1,
                        Optional.of(false),
                        Optional.of(false),
                        Optional.of(false),
                        Optional.of(true),//include references
                        Optional.of(false),//include referencedBy
                        Optional.of(true)//include includeSuperseded
                )
        );
        assertThat(entry1).isNotNull();
        assertThat(entry1.references()).hasSize(1);
        assertThat(entry1.references().getFirst().id()).isEqualTo(newLogID);
        assertThat(sharedUtilityService.htmlContainsReferenceWithId(entry1.text(), newLogID)).isTrue();

        var entry2 = assertDoesNotThrow(
                () -> entryService.getFullEntry(
                        referenceEntry2,
                        Optional.of(false),
                        Optional.of(false),
                        Optional.of(false),
                        Optional.of(true),//include references
                        Optional.of(false),//include referencedBy
                        Optional.of(false)//include includeSuperseded
                )
        );
        assertThat(entry2).isNotNull();
        assertThat(entry2.references()).hasSize(1);
        assertThat(entry2.references().getFirst().id()).isEqualTo(newLogID);
        assertThat(sharedUtilityService.htmlContainsReferenceWithId(entry2.text(), newLogID)).isTrue();
    }

    @Test
    public void testForSupersedeSaveEventDateOfOriginal() {
        var eventAtDate = LocalDateTime.of(2024, 1,1,0,0,0);
        // create the logbook
        var logbook = getTestLogbook();
        // create entry
        String newLogID = assertDoesNotThrow(
                () -> entryService.createNew(
                        EntryNewDTO
                                .builder()
                                .logbooks(Set.of(logbook.id()))
                                .title("Log to be superseded")
                                .text("This is a log for test")
                                .eventAt(eventAtDate)
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );

        String newSupersedeEntryId = assertDoesNotThrow(
                () -> entryService.createNewSupersede(
                        newLogID,
                        EntryNewDTO
                                .builder()
                                .logbooks(Set.of(logbook.id()))
                                .text("This is a superseded for log for log %s".formatted(newLogID))
                                .title("A very wonderful superseded log")
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );
        // get full supersede entry to check the eventAt
        var fullSupersedeEntry = assertDoesNotThrow(
                () -> entryService.getFullEntry(
                        newSupersedeEntryId,
                        Optional.of(false),
                        Optional.of(false),
                        Optional.of(false),
                        Optional.of(false),//include references
                        Optional.of(false),//include referencedBy
                        Optional.of(false)//include includeSuperseded
                )
        );
        assertThat(fullSupersedeEntry).isNotNull();
        assertThat(fullSupersedeEntry.eventAt()).isEqualTo(eventAtDate);
    }
}
