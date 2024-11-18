package edu.stanford.slac.elog_plus.v1.controller;

import edu.stanford.slac.ad.eed.baselib.api.v1.dto.ApiResultResponse;
import edu.stanford.slac.ad.eed.baselib.config.AppProperties;
import edu.stanford.slac.ad.eed.baselib.model.Authorization;
import edu.stanford.slac.ad.eed.baselib.model.LocalGroup;
import edu.stanford.slac.ad.eed.baselib.service.AuthService;
import edu.stanford.slac.elog_plus.api.v1.dto.EntryNewDTO;
import edu.stanford.slac.elog_plus.api.v1.dto.UpdateLogbookDTO;
import edu.stanford.slac.elog_plus.exception.ResourceNotFound;
import edu.stanford.slac.elog_plus.model.Attachment;
import edu.stanford.slac.elog_plus.model.Entry;
import edu.stanford.slac.elog_plus.model.Logbook;
import edu.stanford.slac.elog_plus.repository.LogbookRepository;
import edu.stanford.slac.elog_plus.service.LogbookService;
import edu.stanford.slac.elog_plus.service.DocumentGenerationService;
import edu.stanford.slac.elog_plus.service.SharedUtilityService;
import org.assertj.core.api.AssertionsForClassTypes;
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
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@SpringBootTest()
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(MockitoExtension.class)
@ActiveProfiles({"test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class EntriesControllerLogbookReadWriteAllTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LogbookService logbookService;
    @Autowired
    private LogbookRepository logbookRepository;
    @Autowired
    private AppProperties appProperties;

    @Autowired
    private AuthService authService;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private TestControllerHelperService testControllerHelperService;

    @Autowired
    private DocumentGenerationService documentGenerationService;

    @Autowired
    private SharedUtilityService sharedUtilityService;
    private ApiResultResponse<String> newLogbookApiResultOne = null;
    private ApiResultResponse<String> newLogbookApiResultTwoWriteAll = null;
    private ApiResultResponse<String> newLogbookApiResultThreeReadAll = null;


    @BeforeEach
    public void preTest() {
        mongoTemplate.remove(new Query(), Attachment.class);
        mongoTemplate.remove(new Query(), Logbook.class);
        mongoTemplate.remove(new Query(), Entry.class);
        //reset authorizations
        mongoTemplate.remove(new Query(), Authorization.class);
        mongoTemplate.remove(new Query(), LocalGroup.class);

        appProperties.getRootUserList().clear();
        appProperties.getRootUserList().add("user1@slac.stanford.edu");
        authService.updateRootUser();

        newLogbookApiResultOne = testControllerHelperService.getNewLogbookWithNameWithAuthorization(
                mockMvc,
                Optional.of(
                        "user1@slac.stanford.edu"
                ),
                "new logbook",
                emptyList()
        );
        assertThat(newLogbookApiResultOne.getErrorCode()).isEqualTo(0);
        // create logbook 2
        newLogbookApiResultTwoWriteAll = testControllerHelperService.getNewLogbookWithNameWithAuthorization(
                mockMvc,
                Optional.of(
                        "user1@slac.stanford.edu"
                ),
                "new logbook 2",
                emptyList()
        );
        assertThat(newLogbookApiResultTwoWriteAll.getErrorCode()).isEqualTo(0);

        // make logbook 2 writable to all
        var updateLogbook2WriteAll = assertDoesNotThrow(
                () -> testControllerHelperService.updateLogbook(
                        mockMvc,
                        status().isOk(),
                        Optional.of("user1@slac.stanford.edu"),
                        newLogbookApiResultTwoWriteAll.getPayload(),
                        UpdateLogbookDTO
                                .builder()
                                .name("new-logbook-2")
                                .tags(emptyList())
                                .shifts(emptyList())
                                .writeAll(true)
                                .build()
                )
        );
        assertThat(updateLogbook2WriteAll).isNotNull();
        assertThat(updateLogbook2WriteAll.getPayload()).isTrue();

        // create logbook 3
        newLogbookApiResultThreeReadAll = testControllerHelperService.getNewLogbookWithNameWithAuthorization(
                mockMvc,
                Optional.of("user1@slac.stanford.edu"),
                "new logbook 3",
                emptyList()
        );
        assertThat(newLogbookApiResultThreeReadAll.getErrorCode()).isEqualTo(0);

        // make logbook 3 readable to all
        var updateLogbook3ReadAll = assertDoesNotThrow(
                () -> testControllerHelperService.updateLogbook(
                        mockMvc,
                        status().isOk(),
                        Optional.of("user1@slac.stanford.edu"),
                        newLogbookApiResultThreeReadAll.getPayload(),
                        UpdateLogbookDTO
                                .builder()
                                .name("new-logbook-3")
                                .tags(emptyList())
                                .shifts(emptyList())
                                .readAll(true)
                                .build()
                )
        );
        assertThat(updateLogbook3ReadAll).isNotNull();
        assertThat(updateLogbook3ReadAll.getPayload()).isTrue();
    }

    @Test
    public void readFromAReadAllLogbook() throws Exception {
        // try to create to a write all logbook 2
        var newEntryIdResult =
                assertDoesNotThrow(
                        () -> testControllerHelperService.createNewLog(
                                mockMvc,
                                status().isCreated(),
                                Optional.of("user1@slac.stanford.edu"),
                                EntryNewDTO
                                        .builder()
                                        .logbooks(Set.of(newLogbookApiResultThreeReadAll.getPayload()))
                                        .text("This is a log for test")
                                        .title("A very wonderful log")
                                        .build()
                        )
                );

        assertThat(newEntryIdResult.getErrorCode()).isEqualTo(0);
        // try to read from a read all logbook 3 with user 2 and 3
        var newLogFoundWithUser2 = assertDoesNotThrow(
                () -> testControllerHelperService.getFullLog(
                        mockMvc,
                        status().isOk(),
                        Optional.of("user2@slac.stanford.edu"),
                        newEntryIdResult.getPayload()
                )
        );
        assertThat(newLogFoundWithUser2.getPayload().id()).isEqualTo(newEntryIdResult.getPayload());
        var newLogFoundWithUser3 = assertDoesNotThrow(
                () -> testControllerHelperService.getFullLog(
                        mockMvc,
                        status().isOk(),
                        Optional.of("user2@slac.stanford.edu"),
                        newEntryIdResult.getPayload()
                )
        );
        assertThat(newLogFoundWithUser3.getPayload().id()).isEqualTo(newEntryIdResult.getPayload());
    }

    @Test
    public void writeToWriteAllLogbook() throws Exception {
        // try to create to a write all logbook 2
        var newEntryIdResultWithUser2 =
                assertDoesNotThrow(
                        () -> testControllerHelperService.createNewLog(
                                mockMvc,
                                status().isCreated(),
                                Optional.of("user2@slac.stanford.edu"),
                                EntryNewDTO
                                        .builder()
                                        .logbooks(Set.of(newLogbookApiResultTwoWriteAll.getPayload()))
                                        .text("This is a log for test by user 2")
                                        .title("A very wonderful log")
                                        .build()
                        )
                );
        assertThat(newEntryIdResultWithUser2.getErrorCode()).isEqualTo(0);
        var newEntryIdResultWithUser3 =
                assertDoesNotThrow(
                        () -> testControllerHelperService.createNewLog(
                                mockMvc,
                                status().isCreated(),
                                Optional.of("user3@slac.stanford.edu"),
                                EntryNewDTO
                                        .builder()
                                        .logbooks(Set.of(newLogbookApiResultTwoWriteAll.getPayload()))
                                        .text("This is a log for test by user 3")
                                        .title("A very wonderful log")
                                        .build()
                        )
                );
        assertThat(newEntryIdResultWithUser3.getErrorCode()).isEqualTo(0);
    }

    @Test
    public void searchOnReadAllLogbook() throws Exception {
        int entry_size = 10;
        Set<String> idForLogbook1 = new HashSet<>();
        Set<String> idForLogbook3 = new HashSet<>();
        // try to create to a write all logbook 2
        // write a limited random number of entries to logbook A or logbook B
        for (int i = 0; i < entry_size; i++) {
            var logbookId = i % 2 == 0 ? newLogbookApiResultOne.getPayload() : newLogbookApiResultThreeReadAll.getPayload();
            int finalI = i;
            var entryCreationResult =
                    assertDoesNotThrow(
                            () -> testControllerHelperService.createNewLog(
                                    mockMvc,
                                    status().isCreated(),
                                    Optional.of(
                                            "user1@slac.stanford.edu"
                                    ),
                                    EntryNewDTO
                                            .builder()
                                            .logbooks(Set.of( logbookId ))
                                            .tags(emptySet())
                                            .text(String.format("This is a log for test %d in logbook %s", finalI, logbookId))
                                            .title("Another very wonderful logbook 1 and 2")
                                            .build()
                            )
                    );
            assertThat(entryCreationResult.getErrorCode()).isEqualTo(0);
            if (i % 2 == 0) {
                idForLogbook1.add(entryCreationResult.getPayload());
            } else {
                idForLogbook3.add(entryCreationResult.getPayload());
            }
        }

        // start search on both logbook 1 and 3 with user 2, only entry form logbook 3 should be found
        // giving thew logbook 1 need to get the error for not authorized
        var exceptionForUser3 = assertThrows(
                ResourceNotFound.class,
                () -> testControllerHelperService.submitSearchByGetWithAnchor(
                        mockMvc,
                        status().isNotFound(),
                        Optional.of(
                                "user2@slac.stanford.edu"
                        ),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(entry_size),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(List.of(newLogbookApiResultOne.getPayload(), newLogbookApiResultThreeReadAll.getPayload())),
                        Optional.empty(),
                        Optional.empty()
                )
        );
        AssertionsForClassTypes.assertThat(exceptionForUser3.getErrorCode()).isEqualTo(-1);

        var foundOnLogbook3 = assertDoesNotThrow(
                () -> testControllerHelperService.submitSearchByGetWithAnchor(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user2@slac.stanford.edu"
                        ),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(entry_size),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(List.of(newLogbookApiResultThreeReadAll.getPayload())),
                        Optional.empty(),
                        Optional.empty()
                )
        );
        assertThat(foundOnLogbook3.getErrorCode()).isEqualTo(0);
        assertThat(foundOnLogbook3.getPayload().size()).isEqualTo(idForLogbook3.size());
    }

    @Test
    @DisplayName("Test bug https://github.com/eed-web-application/elog-plus/issues/276")
    public void simulateBug276() throws Exception {
        // remove all readAll and writeAll
        Update u = new Update();
        u.unset("readAll");
        u.unset("writeAll");
        mongoTemplate.updateMulti(
                new Query(),
                u,
                Logbook.class
        );

        for (int i = 0; i < 10; i++) {
            int finalI = i;
            var entryCreationResult =
                    assertDoesNotThrow(
                            () -> testControllerHelperService.createNewLog(
                                    mockMvc,
                                    status().isCreated(),
                                    Optional.of(
                                            "user1@slac.stanford.edu"
                                    ),
                                    EntryNewDTO
                                            .builder()
                                            .logbooks(Set.of(newLogbookApiResultOne.getPayload()))
                                            .tags(emptySet())
                                            .text(String.format("This is a log for test %d in logbook %s", finalI,  newLogbookApiResultOne.getPayload()))
                                            .title("Another very wonderful logbook 1 and 2")
                                            .build()
                            )
                    );
            assertThat(entryCreationResult.getErrorCode()).isEqualTo(0);
        }

        var emptyListWithUnauthorizedUser = assertDoesNotThrow(
                () -> testControllerHelperService.submitSearchByGetWithAnchor(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user2@slac.stanford.edu"
                        ),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(10),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()
                )
        );
        assertThat(emptyListWithUnauthorizedUser.getErrorCode()).isEqualTo(0);

        // also impersonating give empty list
        var emptyListWithUserAndImpersonatingNonAuthUser = assertDoesNotThrow(
                () -> testControllerHelperService.submitSearchByGetWithAnchor(
                        mockMvc,
                        status().isOk(),
                        Optional.of("user1@slac.stanford.edu"),
                        Optional.of("user2@slac.stanford.edu"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(10),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()
                )
        );
        assertThat(emptyListWithUserAndImpersonatingNonAuthUser.getErrorCode()).isEqualTo(0);
    }
}
