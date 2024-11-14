package edu.stanford.slac.elog_plus.v1.controller;

import edu.stanford.slac.ad.eed.baselib.api.v1.dto.ApiResultResponse;
import edu.stanford.slac.ad.eed.baselib.api.v1.dto.AuthorizationOwnerTypeDTO;
import edu.stanford.slac.ad.eed.baselib.api.v1.dto.AuthorizationTypeDTO;
import edu.stanford.slac.ad.eed.baselib.config.AppProperties;
import edu.stanford.slac.ad.eed.baselib.exception.NotAuthorized;
import edu.stanford.slac.ad.eed.baselib.model.Authorization;
import edu.stanford.slac.ad.eed.baselib.model.LocalGroup;
import edu.stanford.slac.ad.eed.baselib.service.AuthService;
import edu.stanford.slac.elog_plus.api.v1.dto.*;
import edu.stanford.slac.elog_plus.exception.ResourceNotFound;
import edu.stanford.slac.elog_plus.model.Attachment;
import edu.stanford.slac.elog_plus.model.Entry;
import edu.stanford.slac.elog_plus.model.Logbook;
import edu.stanford.slac.elog_plus.service.LogbookService;
import edu.stanford.slac.elog_plus.service.DocumentGenerationService;
import edu.stanford.slac.elog_plus.service.SharedUtilityService;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
public class EntriesControllerAuthorizationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LogbookService logbookService;

    @Autowired
    AppProperties appProperties;

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

    private List<String> groupIds;

    @BeforeEach
    public void preTest() {
        mongoTemplate.remove(new Query(), Entry.class);
        mongoTemplate.remove(new Query(), Attachment.class);
        mongoTemplate.remove(new Query(), Logbook.class);

        //reset authorizations
        mongoTemplate.remove(new Query(), Authorization.class);
        mongoTemplate.remove(new Query(), LocalGroup.class);

        appProperties.getRootUserList().clear();
        appProperties.getRootUserList().add("user1@slac.stanford.edu");
        authService.updateRootUser();
        // create default group-1 and group-2
        groupIds = sharedUtilityService.createDefaultGroup();
    }

    @Test
    public void createNewLogUnauthorizedWriteFails() throws Exception {
        var newLogBookResult = assertDoesNotThrow(
                () -> testControllerHelperService.getNewLogbookWithNameWithAuthorization(
                        mockMvc,
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        "LogbookAuthTest1",
                        List.of(
                                NewAuthorizationDTO
                                        .builder()
                                        .ownerType(AuthorizationOwnerTypeDTO.User)
                                        .ownerId("user2@slac.stanford.edu")
                                        .permission(AuthorizationTypeDTO.Read)
                                        .build()
                        )
                )
        );
        assertThat(newLogBookResult.getErrorCode()).isEqualTo(0);
        NotAuthorized unauthorizedWriteOnLogbookByReader =
                assertThrows(
                        NotAuthorized.class,
                        () -> testControllerHelperService.createNewLog(
                                mockMvc,
                                status().isForbidden(),
                                Optional.of(
                                        "user2@slac.stanford.edu"
                                ),
                                EntryNewDTO
                                        .builder()
                                        .logbooks(
                                                List.of(
                                                        newLogBookResult.getPayload()
                                                )
                                        )
                                        .text("This is a log for test")
                                        .title("A very wonderful log")
                                        .build()
                        )
                );

        assertThat(unauthorizedWriteOnLogbookByReader.getErrorCode()).isEqualTo(-1);
    }

    @Test
    public void createNewLogUnauthorizedUnmappedUserToLogbook() throws Exception {
        var newLogBookResult = assertDoesNotThrow(
                () -> testControllerHelperService.getNewLogbookWithNameWithAuthorization(
                        mockMvc,
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        "LogbookAuthTest1",
                        List.of(
                                NewAuthorizationDTO
                                        .builder()
                                        .ownerType(AuthorizationOwnerTypeDTO.User)
                                        .ownerId("user2@slac.stanford.edu")
                                        .permission(AuthorizationTypeDTO.Read)
                                        .build()
                        )
                )
        );
        assertThat(newLogBookResult.getErrorCode()).isEqualTo(0);
        NotAuthorized unauthorizedWriteOnLogbookByReader =
                assertThrows(
                        NotAuthorized.class,
                        () -> testControllerHelperService.createNewLog(
                                mockMvc,
                                status().isForbidden(),
                                Optional.of(
                                        "user3@slac.stanford.edu"
                                ),
                                EntryNewDTO
                                        .builder()
                                        .logbooks(
                                                List.of(
                                                        newLogBookResult.getPayload()
                                                )
                                        )
                                        .text("This is a log for test")
                                        .title("A very wonderful log")
                                        .build()
                        )
                );

        assertThat(unauthorizedWriteOnLogbookByReader.getErrorCode()).isEqualTo(-1);
    }

    @Test
    public void createNewLogSuccessWithWriterUserAndAdmin() throws Exception {
        var newLogBookResult = assertDoesNotThrow(
                () -> testControllerHelperService.getNewLogbookWithNameWithAuthorization(
                        mockMvc,
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        "LogbookAuthTest1",
                        List.of(
                                NewAuthorizationDTO
                                        .builder()
                                        .ownerType(AuthorizationOwnerTypeDTO.User)
                                        .ownerId("user2@slac.stanford.edu")
                                        .permission(AuthorizationTypeDTO.Write)
                                        .build(),
                                NewAuthorizationDTO
                                        .builder()
                                        .ownerType(AuthorizationOwnerTypeDTO.User)
                                        .ownerId("user3@slac.stanford.edu")
                                        .permission(AuthorizationTypeDTO.Admin)
                                        .build()
                        )
                )
        );
        assertThat(newLogBookResult.getErrorCode()).isEqualTo(0);
        var newLogIdForWriter =
                assertDoesNotThrow(
                        () -> testControllerHelperService.createNewLog(
                                mockMvc,
                                status().isCreated(),
                                Optional.of(
                                        "user2@slac.stanford.edu"
                                ),
                                EntryNewDTO
                                        .builder()
                                        .logbooks(
                                                List.of(
                                                        newLogBookResult.getPayload()
                                                )
                                        )
                                        .text("This is a log for test")
                                        .title("A very wonderful log")
                                        .build()
                        )
                );

        assertThat(newLogIdForWriter.getErrorCode()).isEqualTo(0);
        var newLogIdForAdmin =
                assertDoesNotThrow(
                        () -> testControllerHelperService.createNewLog(
                                mockMvc,
                                status().isCreated(),
                                Optional.of(
                                        "user3@slac.stanford.edu"
                                ),
                                EntryNewDTO
                                        .builder()
                                        .logbooks(
                                                List.of(
                                                        newLogBookResult.getPayload()
                                                )
                                        )
                                        .text("This is a log for test")
                                        .title("A very wonderful log")
                                        .build()
                        )
                );

        assertThat(newLogIdForWriter.getErrorCode()).isEqualTo(0);
    }

    @Test
    public void createNewLogSuccessWithWriterGroupAuth() throws Exception {
        var newLogBookResult = assertDoesNotThrow(
                () -> testControllerHelperService.getNewLogbookWithNameWithAuthorization(
                        mockMvc,
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        "LogbookAuthTest1",
                        List.of(
                                NewAuthorizationDTO
                                        .builder()
                                        .ownerType(AuthorizationOwnerTypeDTO.Group)
                                        // group-2 id
                                        .ownerId(groupIds.get(1))
                                        .permission(AuthorizationTypeDTO.Write)
                                        .build()
                        )
                )
        );
        assertThat(newLogBookResult.getErrorCode()).isEqualTo(0);
        var newLogIdForWriter =
                assertDoesNotThrow(
                        () -> testControllerHelperService.createNewLog(
                                mockMvc,
                                status().isCreated(),
                                Optional.of(
                                        "user2@slac.stanford.edu"
                                ),
                                EntryNewDTO
                                        .builder()
                                        .logbooks(
                                                List.of(
                                                        newLogBookResult.getPayload()
                                                )
                                        )
                                        .text("This is a log for test")
                                        .title("A very wonderful log")
                                        .build()
                        )
                );

        assertThat(newLogIdForWriter.getErrorCode()).isEqualTo(0);
    }

    @Test
    public void createNewLogAcrossLogbookAndCheckAuthOnlyOnOne() throws Exception {
        var newLogBookResult1 = assertDoesNotThrow(
                () -> testControllerHelperService.getNewLogbookWithNameWithAuthorization(
                        mockMvc,
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        "LogbookAuthTest1",
                        List.of(
                                NewAuthorizationDTO
                                        .builder()
                                        .ownerType(AuthorizationOwnerTypeDTO.User)
                                        .ownerId("user2@slac.stanford.edu")
                                        .permission(AuthorizationTypeDTO.Read)
                                        .build()
                        )
                )
        );
        assertThat(newLogBookResult1.getErrorCode()).isEqualTo(0);
        var newLogBookResult2 = assertDoesNotThrow(
                () -> testControllerHelperService.getNewLogbookWithNameWithAuthorization(
                        mockMvc,
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        "LogbookAuthTest2",
                        List.of(
                                NewAuthorizationDTO
                                        .builder()
                                        .ownerType(AuthorizationOwnerTypeDTO.User)
                                        .ownerId("user3@slac.stanford.edu")
                                        .permission(AuthorizationTypeDTO.Read)
                                        .build()
                        )
                )
        );
        assertThat(newLogBookResult2.getErrorCode()).isEqualTo(0);
        ApiResultResponse<String> newLogID =
                assertDoesNotThrow(
                        () -> testControllerHelperService.createNewLog(
                                mockMvc,
                                status().isCreated(),
                                Optional.of(
                                        "user1@slac.stanford.edu"
                                ),
                                EntryNewDTO
                                        .builder()
                                        .logbooks(
                                                List.of(
                                                        newLogBookResult1.getPayload(),
                                                        newLogBookResult2.getPayload()
                                                )
                                        )
                                        .text("This is a log for test")
                                        .title("A very wonderful log")
                                        .build()
                        )
                );

        assertThat(newLogID.getErrorCode()).isEqualTo(0);

        //fetch the entry with user that can read only from on logbook
        var entryForUser2 = testControllerHelperService.getFullLog(
                mockMvc,
                Optional.of(
                        "user2@slac.stanford.edu"
                ),
                newLogID.getPayload()
        );
        assertThat(entryForUser2.getErrorCode()).isEqualTo(0);
        assertThat(entryForUser2.getPayload().logbooks()).extracting("id").contains(newLogBookResult1.getPayload());

        var entryForUser3 = testControllerHelperService.getFullLog(
                mockMvc,
                Optional.of(
                        "user3@slac.stanford.edu"
                ),
                newLogID.getPayload()
        );
        assertThat(entryForUser3.getErrorCode()).isEqualTo(0);
        assertThat(entryForUser3.getPayload().logbooks()).extracting("id").contains(newLogBookResult2.getPayload());
    }

    @Test
    public void testEntriesSearchForAuthorization() {
        var newLogBookResult1 = assertDoesNotThrow(
                () -> testControllerHelperService.getNewLogbookWithNameWithAuthorization(
                        mockMvc,
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        "LogbookAuthTest1",
                        List.of(
                                NewAuthorizationDTO
                                        .builder()
                                        .ownerType(AuthorizationOwnerTypeDTO.User)
                                        .ownerId("user2@slac.stanford.edu")
                                        .permission(AuthorizationTypeDTO.Read)
                                        .build()
                        )
                )
        );
        assertThat(newLogBookResult1.getErrorCode()).isEqualTo(0);
        var newLogBookResult2 = assertDoesNotThrow(
                () -> testControllerHelperService.getNewLogbookWithNameWithAuthorization(
                        mockMvc,
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        "LogbookAuthTest2",
                        List.of(
                                NewAuthorizationDTO
                                        .builder()
                                        .ownerType(AuthorizationOwnerTypeDTO.User)
                                        .ownerId("user3@slac.stanford.edu")
                                        .permission(AuthorizationTypeDTO.Read)
                                        .build()
                        )
                )
        );

        // add one entry into first logbook
        ApiResultResponse<String> newLogID1 =
                assertDoesNotThrow(
                        () -> testControllerHelperService.createNewLog(
                                mockMvc,
                                status().isCreated(),
                                Optional.of(
                                        "user1@slac.stanford.edu"
                                ),
                                EntryNewDTO
                                        .builder()
                                        .logbooks(
                                                List.of(
                                                        newLogBookResult1.getPayload()
                                                )
                                        )
                                        .text("This is a log for test")
                                        .title("A very wonderful log for logbook1")
                                        .build()
                        )
                );

        assertThat(newLogID1.getErrorCode()).isEqualTo(0);

        // add two entry into second logbook
        ApiResultResponse<String> newLogID2 =
                assertDoesNotThrow(
                        () -> testControllerHelperService.createNewLog(
                                mockMvc,
                                status().isCreated(),
                                Optional.of(
                                        "user1@slac.stanford.edu"
                                ),
                                EntryNewDTO
                                        .builder()
                                        .logbooks(
                                                List.of(
                                                        newLogBookResult1.getPayload(),
                                                        newLogBookResult2.getPayload()
                                                )
                                        )
                                        .text("This is a log for test")
                                        .title("A very wonderful logbook 1 and 2")
                                        .build()
                        )
                );

        assertThat(newLogID2.getErrorCode()).isEqualTo(0);

        ApiResultResponse<String> newLogID3 =
                assertDoesNotThrow(
                        () -> testControllerHelperService.createNewLog(
                                mockMvc,
                                status().isCreated(),
                                Optional.of(
                                        "user1@slac.stanford.edu"
                                ),
                                EntryNewDTO
                                        .builder()
                                        .logbooks(
                                                List.of(
                                                        newLogBookResult1.getPayload(),
                                                        newLogBookResult2.getPayload()
                                                )
                                        )
                                        .text("This is a log for test")
                                        .title("Another very wonderful logbook 1 and 2")
                                        .build()
                        )
                );

        assertThat(newLogID3.getErrorCode()).isEqualTo(0);

        //execute search with user two
        var searchResult = assertDoesNotThrow(
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
                        Optional.of(List.of(newLogBookResult1.getPayload())),
                        Optional.empty(),
                        Optional.empty()
                )
        );
        assertThat(searchResult).isNotNull();
        assertThat(searchResult.getErrorCode()).isEqualTo(0);
        assertThat(searchResult.getPayload()).hasSize(3);
        assertThat(searchResult.getPayload())
                .extracting(EntrySummaryDTO::id)
                .contains(
                        newLogID1.getPayload(),
                        newLogID2.getPayload(),
                        newLogID3.getPayload()
                );

        // search same user on al the authorized logbook
        searchResult = assertDoesNotThrow(
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
        assertThat(searchResult).isNotNull();
        assertThat(searchResult.getErrorCode()).isEqualTo(0);
        assertThat(searchResult.getPayload()).hasSize(3);
        assertThat(searchResult.getPayload())
                .extracting(EntrySummaryDTO::id)
                .contains(
                        newLogID1.getPayload(),
                        newLogID2.getPayload(),
                        newLogID3.getPayload()
                );

        //execute search with user three
        searchResult = assertDoesNotThrow(
                () -> testControllerHelperService.submitSearchByGetWithAnchor(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user3@slac.stanford.edu"
                        ),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(10),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(List.of(newLogBookResult2.getPayload())),
                        Optional.empty(),
                        Optional.empty()
                )
        );
        assertThat(searchResult).isNotNull();
        assertThat(searchResult.getErrorCode()).isEqualTo(0);
        assertThat(searchResult.getPayload()).hasSize(2);
        assertThat(searchResult.getPayload())
                .extracting(EntrySummaryDTO::id)
                .contains(
                        newLogID2.getPayload(),
                        newLogID3.getPayload()
                );
    }

    @Test
    public void searchFailsOnNonAuthorizedLogbook() {
        var newLogBookResult1 = assertDoesNotThrow(
                () -> testControllerHelperService.getNewLogbookWithNameWithAuthorization(
                        mockMvc,
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        "LogbookAuthTest1",
                        List.of(
                                NewAuthorizationDTO
                                        .builder()
                                        .ownerType(AuthorizationOwnerTypeDTO.User)
                                        .ownerId("user2@slac.stanford.edu")
                                        .permission(AuthorizationTypeDTO.Read)
                                        .build()
                        )
                )
        );
        assertThat(newLogBookResult1.getErrorCode()).isEqualTo(0);
        var newLogBookResult2 = assertDoesNotThrow(
                () -> testControllerHelperService.getNewLogbookWithNameWithAuthorization(
                        mockMvc,
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        "LogbookAuthTest2",
                        List.of(
                                NewAuthorizationDTO
                                        .builder()
                                        .ownerType(AuthorizationOwnerTypeDTO.User)
                                        .ownerId("user3@slac.stanford.edu")
                                        .permission(AuthorizationTypeDTO.Read)
                                        .build()
                        )
                )
        );


        // this should give exception because user 2 is not authorized on logbook 2
        var exceptionForUser2 = assertThrows(
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
                        Optional.of(10),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(List.of(newLogBookResult2.getPayload())),
                        Optional.empty(),
                        Optional.empty()
                )
        );
        assertThat(exceptionForUser2.getErrorCode()).isEqualTo(-1);


        // this should give exception because user 3 is not authorized on logbook 1
        var exceptionForUser3 = assertThrows(
                ResourceNotFound.class,
                () -> testControllerHelperService.submitSearchByGetWithAnchor(
                        mockMvc,
                        status().isNotFound(),
                        Optional.of(
                                "user3@slac.stanford.edu"
                        ),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(10),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(List.of(newLogBookResult1.getPayload())),
                        Optional.empty(),
                        Optional.empty()
                )
        );
        assertThat(exceptionForUser3.getErrorCode()).isEqualTo(-1);
    }

    /**
     * This test is to check if the entries returned match the authorization
     * fix/183-get-entries-responding-with-entries-from-logbooks-the-user-does-not-have-read-access-to
     */
    @Test
    public void testEntriesReturnedMatchAuthorization() {
        // and array that contains the entry id for the specific logbook
        int entry_size = 10;
        Set<String> idForLogbookA = new HashSet<>();
        Set<String> idForLogbookB = new HashSet<>();
        var logbookA = assertDoesNotThrow(
                () -> testControllerHelperService.getNewLogbookWithNameWithAuthorization(
                        mockMvc,
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        "LogbookA",
                        List.of(
                                NewAuthorizationDTO
                                        .builder()
                                        .ownerType(AuthorizationOwnerTypeDTO.User)
                                        .ownerId("user2@slac.stanford.edu")
                                        .permission(AuthorizationTypeDTO.Read)
                                        .build()
                        )
                )
        );
        // create tags for logbookA
        var tagLogbookAResult = assertDoesNotThrow(
                () -> testControllerHelperService.createNewLogbookTags(
                        mockMvc,
                        status().isCreated(),
                        Optional.of("user1@slac.stanford.edu"),
                        logbookA.getPayload(),
                        NewTagDTO.builder().name("TagLogbookA").build()
                )
        );
        assertThat(tagLogbookAResult.getErrorCode()).isEqualTo(0);

        var logbookB = assertDoesNotThrow(
                () -> testControllerHelperService.getNewLogbookWithNameWithAuthorization(
                        mockMvc,
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        "LogbookB",
                        List.of(
                                NewAuthorizationDTO
                                        .builder()
                                        .ownerType(AuthorizationOwnerTypeDTO.User)
                                        .ownerId("user3@slac.stanford.edu")
                                        .permission(AuthorizationTypeDTO.Read)
                                        .build()
                        )
                )
        );
        // create tags for logbookA
        var tagLogbookBResult = assertDoesNotThrow(
                () -> testControllerHelperService.createNewLogbookTags(
                        mockMvc,
                        status().isCreated(),
                        Optional.of("user1@slac.stanford.edu"),
                        logbookB.getPayload(),
                        NewTagDTO.builder().name("TagLogbookB").build()
                )
        );
        assertThat(tagLogbookBResult.getErrorCode()).isEqualTo(0);

        // write a limited random number of entries to logbook A or logbook B
        for (int i = 0; i < entry_size; i++) {
            var logbookId = i % 2 == 0 ? logbookA.getPayload() : logbookB.getPayload();
            var tagId = i % 2 == 0 ? tagLogbookAResult.getPayload() : tagLogbookBResult.getPayload();
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
                                            .logbooks(
                                                    List.of(
                                                            logbookId
                                                    )
                                            )
                                            .tags(
                                                    List.of(
                                                            tagId
                                                    )
                                            )
                                            .text(String.format("This is a log for test %d in logbook %s", finalI, logbookId))
                                            .title("Another very wonderful logbook 1 and 2")
                                            .build()
                            )
                    );
            assertThat(entryCreationResult.getErrorCode()).isEqualTo(0);
            if (i % 2 == 0) {
                idForLogbookA.add(entryCreationResult.getPayload());
            } else {
                idForLogbookB.add(entryCreationResult.getPayload());
            }
        }

        // giving thew logbook i need to get the error for not authorized
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
                        Optional.of(List.of(logbookA.getPayload(), logbookB.getPayload())),
                        Optional.empty(),
                        Optional.empty()
                )
        );
        assertThat(exceptionForUser3.getErrorCode()).isEqualTo(-1);

        // check entries for logbook A
        var entriesForLogbookAWithUser2 = assertDoesNotThrow(
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
                        Optional.of(List.of(logbookA.getPayload())),
                        Optional.empty(),
                        Optional.empty()
                )
        );
        assertThat(entriesForLogbookAWithUser2.getErrorCode()).isEqualTo(0);
        assertThat(entriesForLogbookAWithUser2.getPayload())
                .hasSize(idForLogbookA.size())
                .extracting(EntrySummaryDTO::id)
                .contains(idForLogbookA.toArray(new String[idForLogbookA.size()]));


        entriesForLogbookAWithUser2 = assertDoesNotThrow(
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
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()
                )
        );
        assertThat(entriesForLogbookAWithUser2.getErrorCode()).isEqualTo(0);
        assertThat(entriesForLogbookAWithUser2.getPayload())
                .hasSize(idForLogbookA.size())
                .extracting(EntrySummaryDTO::id)
                .contains(idForLogbookA.toArray(new String[idForLogbookA.size()]));

        // check entries for logbook B
        var entriesForLogbookAWithUser3 = assertDoesNotThrow(
                () -> testControllerHelperService.submitSearchByGetWithAnchor(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user3@slac.stanford.edu"
                        ),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(entry_size),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(List.of(logbookB.getPayload())),
                        Optional.empty(),
                        Optional.empty()
                )
        );
        assertThat(entriesForLogbookAWithUser3.getErrorCode()).isEqualTo(0);
        assertThat(entriesForLogbookAWithUser3.getPayload())
                .hasSize(idForLogbookB.size())
                .extracting(EntrySummaryDTO::id)
                .contains(idForLogbookB.toArray(new String[idForLogbookB.size()]));

        entriesForLogbookAWithUser3 = assertDoesNotThrow(
                () -> testControllerHelperService.submitSearchByGetWithAnchor(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user3@slac.stanford.edu"
                        ),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(entry_size),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()
                )
        );
        assertThat(entriesForLogbookAWithUser3.getErrorCode()).isEqualTo(0);
        assertThat(entriesForLogbookAWithUser3.getPayload())
                .hasSize(idForLogbookB.size())
                .extracting(EntrySummaryDTO::id)
                .contains(idForLogbookB.toArray(new String[idForLogbookB.size()]));
    }
}
