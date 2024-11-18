package edu.stanford.slac.elog_plus.service;

import edu.stanford.slac.elog_plus.api.v1.dto.*;
import edu.stanford.slac.elog_plus.model.Entry;
import edu.stanford.slac.elog_plus.model.Logbook;
import edu.stanford.slac.elog_plus.repository.AttachmentRepository;
import edu.stanford.slac.elog_plus.utility.DateUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

import static org.assertj.core.api.AssertionsForClassTypes.not;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@AutoConfigureMockMvc
@SpringBootTest()
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(MockitoExtension.class)
@ActiveProfiles({"test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class EntryServiceShiftTest {
    @Autowired
    private EntryService entryService;
    @Autowired
    private LogbookService logbookService;
    @Autowired
    private AttachmentService attachmentService;
    @Autowired
    private AttachmentRepository attachmentRepository;
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


    @Test()
    @DisplayName("Test for bug https://github.com/eed-web-application/elog-plus/issues/265")
    public void fixBug256() {
        var testLogbook = getTestLogbook();

        // update logbook with the right shift
        LogbookDTO logbookDTO = assertDoesNotThrow(
                () -> logbookService.update(
                        testLogbook.id(),
                        UpdateLogbookDTO
                                .builder()
                                .name(testLogbook.name())
                                .tags(testLogbook.tags())
                                .shifts(
                                        List.of(
                                                ShiftDTO
                                                        .builder()
                                                        .name("Owl Shift")
                                                        .from(DateUtilities.toUTCString(LocalTime.of(0, 0)))
                                                        .to(DateUtilities.toUTCString(LocalTime.of(7, 59)))
                                                        .build(),
                                                ShiftDTO
                                                        .builder()
                                                        .name("Day Shift")
                                                        .from(DateUtilities.toUTCString(LocalTime.of(8, 0)))
                                                        .to(DateUtilities.toUTCString(LocalTime.of(15, 59)))
                                                        .build(),
                                                ShiftDTO
                                                        .builder()
                                                        .name("Swing Shift")
                                                        .from(DateUtilities.toUTCString(LocalTime.of(16, 0)))
                                                        .to(DateUtilities.toUTCString(LocalTime.of(23, 59)))
                                                        .build()
                                        )
                                )
                                .build()
                )
        );

        // create a new entry
        String newEntry = assertDoesNotThrow(
                () -> entryService.createNew(
                        EntryNewDTO
                                .builder()
                                .logbooks(Set.of(testLogbook.id()))
                                .text("This is a log for test")
                                .title("A very wonderful log")
                                .eventAt(LocalDateTime.of(2024, 8, 15, 17, 39, 5))
                                .build(),
                        sharedUtilityService.getPersonForEmail("user1@slac.stanford.edu")
                )
        );

        // fetch the entry
        EntryDTO entryDTO = assertDoesNotThrow(
                () -> entryService.getFullEntry(newEntry)
        );
        // check for the shift
        assertThat(entryDTO.shifts()).isNotNull();
        assertThat(entryDTO.shifts().size()).isEqualTo(1);
        assertThat(entryDTO.shifts().getFirst().name()).isEqualTo("Swing Shift");
    }

}
