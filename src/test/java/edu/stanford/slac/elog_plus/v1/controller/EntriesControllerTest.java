package edu.stanford.slac.elog_plus.v1.controller;

import edu.stanford.slac.ad.eed.baselib.api.v1.dto.ApiResultResponse;
import edu.stanford.slac.ad.eed.baselib.config.AppProperties;
import edu.stanford.slac.ad.eed.baselib.model.Authorization;
import edu.stanford.slac.ad.eed.baselib.service.AuthService;
import edu.stanford.slac.elog_plus.api.v1.dto.*;
import edu.stanford.slac.elog_plus.exception.EntryNotFound;
import edu.stanford.slac.elog_plus.exception.SupersedeAlreadyCreated;
import edu.stanford.slac.elog_plus.exception.TagNotFound;
import edu.stanford.slac.elog_plus.model.Attachment;
import edu.stanford.slac.elog_plus.model.Entry;
import edu.stanford.slac.elog_plus.model.Logbook;
import edu.stanford.slac.elog_plus.service.LogbookService;
import edu.stanford.slac.elog_plus.utility.DateUtilities;
import edu.stanford.slac.elog_plus.service.DocumentGenerationService;
import org.assertj.core.api.AssertionsForClassTypes;
import org.assertj.core.api.AssertionsForInterfaceTypes;
import org.jsoup.nodes.Element;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static edu.stanford.slac.elog_plus.api.v1.mapper.EntryMapper.ELOG_ENTRY_REF;
import static edu.stanford.slac.elog_plus.api.v1.mapper.EntryMapper.ELOG_ENTRY_REF_ID;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@SpringBootTest()
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(MockitoExtension.class)
@ActiveProfiles({"test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class EntriesControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LogbookService logbookService;

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

    @BeforeEach
    public void preTest() {
        mongoTemplate.remove(new Query(), Entry.class);
        mongoTemplate.remove(new Query(), Attachment.class);
        mongoTemplate.remove(new Query(), Logbook.class);

        //reset authorizations
        mongoTemplate.remove(new Query(), Authorization.class);
        appProperties.getRootUserList().clear();
        appProperties.getRootUserList().add("user1@slac.stanford.edu");
        appProperties.getRootUserList().add(appProperties.getInternalServiceTokenEmail());
        authService.updateRootUser();
    }

    @Test
    public void createNewLog() throws Exception {
        var newLogBookResult = testControllerHelperService.getTestLogbook(mockMvc);
        ApiResultResponse<String> newLogID =
                assertDoesNotThrow(
                        () ->
                                testControllerHelperService.createNewLog(
                                        mockMvc,
                                        status().isCreated(),
                                        Optional.of(
                                                "user1@slac.stanford.edu"
                                        ),
                                        EntryNewDTO
                                                .builder()
                                                .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                                .text("This is a log for test")
                                                .title("A very wonderful log")
                                                .build()
                                )
                );

        AssertionsForClassTypes.assertThat(newLogID).isNotNull();
        AssertionsForClassTypes.assertThat(newLogID.getErrorCode()).isEqualTo(0);
    }

    //@Test
    public void createNewLogUsingServiceJWT() throws Exception {
        var newLogBookResult = testControllerHelperService.getTestLogbook(mockMvc, appProperties.getInternalServiceTokenEmail());
        ApiResultResponse<String> newLogID =
                assertDoesNotThrow(
                        () ->
                                testControllerHelperService.createNewLog(
                                        mockMvc,
                                        status().isCreated(),
                                        Optional.of(
                                                appProperties.getInternalServiceTokenEmail()
                                        ),
                                        EntryNewDTO
                                                .builder()
                                                .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                                .text("This is a log for test")
                                                .title("A very wonderful log")
                                                .build()
                                )
                );

        AssertionsForClassTypes.assertThat(newLogID).isNotNull();
        AssertionsForClassTypes.assertThat(newLogID.getErrorCode()).isEqualTo(0);

        var fullLog = testControllerHelperService.getFullLog(
                mockMvc,
                Optional.of(appProperties.getInternalServiceTokenEmail()),
                newLogID.getPayload()
        );
        AssertionsForClassTypes.assertThat(fullLog).isNotNull();
        AssertionsForClassTypes.assertThat(fullLog.getErrorCode()).isEqualTo(0);
    }

    @Test
    public void createNewLogWithAttachment() throws Exception {
        ApiResultResponse<String> newAttachmentID = null;
        try (InputStream is = documentGenerationService.getTestPng()) {
            newAttachmentID = assertDoesNotThrow(
                    () -> testControllerHelperService.newAttachment(
                            mockMvc,
                            status().isCreated(),
                            Optional.of(
                                    "user1@slac.stanford.edu"
                            ),
                            new MockMultipartFile(
                                    "uploadFile",
                                    "test.png",
                                    MediaType.IMAGE_PNG_VALUE,
                                    is
                            )
                    )
            );
        }
        assertThat(newAttachmentID.getErrorCode()).isEqualTo(0);

        var newLogBookResult = testControllerHelperService.getTestLogbook(mockMvc);
        ApiResultResponse<String> finalNewAttachmentID = newAttachmentID;
        ApiResultResponse<String> newLogID =
                assertDoesNotThrow(
                        () ->
                                testControllerHelperService.createNewLog(
                                        mockMvc,
                                        status().isCreated(),
                                        Optional.of(
                                                "user1@slac.stanford.edu"
                                        ),
                                        EntryNewDTO
                                                .builder()
                                                .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                                .text("This is a log for test")
                                                .title("A very wonderful log")
                                                .attachments(
                                                        Set.of(
                                                                finalNewAttachmentID.getPayload()
                                                        )
                                                )
                                                .build()
                                )
                );

        AssertionsForClassTypes.assertThat(newLogID).isNotNull();
        AssertionsForClassTypes.assertThat(newLogID.getErrorCode()).isEqualTo(0);
        await().atMost(30, SECONDS).pollDelay(2, MILLISECONDS).until(
                () -> {
                    ApiResultResponse<EntryDTO> logDto = assertDoesNotThrow(
                            () ->
                                    testControllerHelperService.getFullLog(
                                            mockMvc,
                                            Optional.of(
                                                    "user1@slac.stanford.edu"
                                            ),
                                            newLogID.getPayload()
                                    )
                    );
                    assertThat(logDto.getErrorCode()).isEqualTo(0);
                    return logDto.getPayload().attachments().size() == 1 &&
                            logDto.getPayload().attachments().get(0).previewState().compareTo(Attachment.PreviewProcessingState.Completed.name()) == 0;
                }
        );

        ApiResultResponse<EntryDTO> logDto = assertDoesNotThrow(
                () ->
                        testControllerHelperService.getFullLog(
                                mockMvc,
                                Optional.of(
                                        "user1@slac.stanford.edu"
                                ),
                                newLogID.getPayload()
                        )
        );
        assertThat(logDto.getErrorCode()).isEqualTo(0);
        assertThat(logDto.getPayload().attachments().size()).isEqualTo(1);
        assertThat(logDto.getPayload().attachments().get(0).miniPreview()).isNotNull();
    }

    @Test
    public void failCreatingNewLogWitWrongTag() throws Exception {
        var newLogBookResult = testControllerHelperService.getTestLogbook(mockMvc);

        TagNotFound tagNotFound =
                assertThrows(
                        TagNotFound.class,
                        () ->
                                testControllerHelperService.createNewLog(
                                        mockMvc,
                                        status().is4xxClientError(),
                                        Optional.of(
                                                "user1@slac.stanford.edu"
                                        ),
                                        EntryNewDTO
                                                .builder()
                                                .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                                .text("This is a log for test")
                                                .title("A very wonderful log")
                                                .tags(
                                                        Set.of(
                                                                "wrong id"
                                                        )
                                                )
                                                .build()
                                )
                );

        assertThat(tagNotFound.getErrorCode()).isEqualTo(-4);
    }

    @Test
    public void createNewLogAndSearchWithPaging() throws Exception {
        var newLogBookResult = testControllerHelperService.getTestLogbook(mockMvc);
        ApiResultResponse<String> newLogID =
                assertDoesNotThrow(
                        () ->
                                testControllerHelperService.createNewLog(
                                        mockMvc,
                                        status().isCreated(),
                                        Optional.of(
                                                "user1@slac.stanford.edu"
                                        ),
                                        EntryNewDTO
                                                .builder()
                                                .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                                .text("This is a log for test")
                                                .title("A very wonderful log")
                                                .build()
                                )
                );

        AssertionsForClassTypes.assertThat(newLogID).isNotNull();
        AssertionsForClassTypes.assertThat(newLogID.getErrorCode()).isEqualTo(0);

        var queryResult = testControllerHelperService.submitSearchByGetWithAnchor(
                mockMvc,
                status().isOk(),
                Optional.of(
                        "user1@slac.stanford.edu"
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
        );
        AssertionsForClassTypes.assertThat(queryResult.getPayload().size()).isEqualTo(1);
    }

    @Test
    public void failsFetchingInvalidEntry() {
        EntryNotFound newLogID =
                assertThrows(
                        EntryNotFound.class,
                        () ->
                                testControllerHelperService.getFullLog(
                                        mockMvc,
                                        status().is4xxClientError(),
                                        Optional.of(
                                                "user1@slac.stanford.edu"
                                        ),
                                        "bad-id"
                                )
                );
        AssertionsForClassTypes.assertThat(newLogID.getErrorCode()).isEqualTo(-2);
    }

    @Test
    public void fetchFullLog() {
        var newLogBookResult = testControllerHelperService.getTestLogbook(mockMvc);
        ApiResultResponse<String> newLogID =
                assertDoesNotThrow(
                        () ->
                                testControllerHelperService.createNewLog(
                                        mockMvc,
                                        status().isCreated(),
                                        Optional.of(
                                                "user1@slac.stanford.edu"
                                        ),
                                        EntryNewDTO
                                                .builder()
                                                .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                                .text("This is a log for test")
                                                .title("A very wonderful log")
                                                .build()
                                )
                );
        AssertionsForClassTypes.assertThat(newLogID.getErrorCode()).isEqualTo(0);

        ApiResultResponse<EntryDTO> fullLog = assertDoesNotThrow(
                () ->
                        testControllerHelperService.getFullLog(
                                mockMvc,
                                status().isOk(),
                                Optional.of(
                                        "user1@slac.stanford.edu"
                                ),
                                newLogID.getPayload()
                        )
        );
        AssertionsForClassTypes.assertThat(newLogID.getErrorCode()).isEqualTo(0);
        AssertionsForClassTypes.assertThat(fullLog.getPayload().id()).isEqualTo(newLogID.getPayload());
    }

    @Test
    public void createNewSupersedeLog() throws Exception {
        var newLogBookResult = testControllerHelperService.getTestLogbook(mockMvc);
        ApiResultResponse<String> newLogIDResult =
                assertDoesNotThrow(
                        () ->
                                testControllerHelperService.createNewLog(
                                        mockMvc,
                                        status().isCreated(),
                                        Optional.of(
                                                "user1@slac.stanford.edu"
                                        ),
                                        EntryNewDTO
                                                .builder()
                                                .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                                .text("This is a log for test")
                                                .title("A very wonderful log")
                                                .build()
                                )
                );

        AssertionsForClassTypes.assertThat(newLogIDResult).isNotNull();
        AssertionsForClassTypes.assertThat(newLogIDResult.getErrorCode()).isEqualTo(0);
        //create supersede
        ApiResultResponse<String> newSupersedeLogIDResult = assertDoesNotThrow(
                () -> testControllerHelperService.createNewSupersedeLog(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        newLogIDResult.getPayload(),
                        EntryNewDTO
                                .builder()
                                .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                .text("This is a log for test")
                                .title("A very wonderful supersede log")
                                .build()
                )
        );

        //check old log for supersede info
        ApiResultResponse<EntryDTO> oldFull = assertDoesNotThrow(
                () ->
                        testControllerHelperService.getFullLog(
                                mockMvc,
                                status().isOk(),
                                Optional.of(
                                        "user1@slac.stanford.edu"
                                ),
                                newLogIDResult.getPayload(),
                                false,
                                false,
                                false,
                                false,
                                false,
                                true

                        )
        );

        assertThat(oldFull.getErrorCode()).isEqualTo(0);
        assertThat(oldFull.getPayload().supersededBy()).isNotNull();
        assertThat(oldFull.getPayload().supersededBy().id()).isEqualTo(newSupersedeLogIDResult.getPayload());

        // the search api now should return only the new log and not the superseded on
        var queryResult = testControllerHelperService.submitSearchByGetWithAnchor(
                mockMvc,
                status().isOk(),
                Optional.of(
                        "user1@slac.stanford.edu"
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
        );
        AssertionsForClassTypes.assertThat(queryResult).isNotNull();
        AssertionsForClassTypes.assertThat(queryResult.getPayload().size()).isEqualTo(1);
    }

    @Test
    public void createDoubleSupersedeFailed() throws Exception {
        var newLogBookResult = testControllerHelperService.getTestLogbook(mockMvc);
        ApiResultResponse<String> newLogIDResult =
                assertDoesNotThrow(
                        () ->
                                testControllerHelperService.createNewLog(
                                        mockMvc,
                                        status().isCreated(),
                                        Optional.of(
                                                "user1@slac.stanford.edu"
                                        ),
                                        EntryNewDTO
                                                .builder()
                                                .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                                .text("This is a log for test")
                                                .title("A very wonderful log")
                                                .build()
                                )
                );

        AssertionsForClassTypes.assertThat(newLogIDResult).isNotNull();
        AssertionsForClassTypes.assertThat(newLogIDResult.getErrorCode()).isEqualTo(0);
        //create supersede
        ApiResultResponse<String> newSupersedeLogIDResult = assertDoesNotThrow(
                () -> testControllerHelperService.createNewSupersedeLog(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        newLogIDResult.getPayload(),
                        EntryNewDTO
                                .builder()
                                .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                .text("This is a log for test")
                                .title("A very wonderful supersede log")
                                .build()
                )
        );
        AssertionsForClassTypes.assertThat(newSupersedeLogIDResult).isNotNull();
        AssertionsForClassTypes.assertThat(newSupersedeLogIDResult.getErrorCode()).isEqualTo(0);

        SupersedeAlreadyCreated exception = assertThrows(
                SupersedeAlreadyCreated.class,
                () -> testControllerHelperService.createNewSupersedeLog(
                        mockMvc,
                        status().is4xxClientError(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        newLogIDResult.getPayload(),
                        EntryNewDTO
                                .builder()
                                .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                .text("This is a log for test")
                                .title("A very wonderful supersede log")
                                .build()
                )
        );
        assertThat(exception.getErrorCode()).isEqualTo(-3);
    }

    @Test
    public void createSupersedeFailedOnNotFoundEntry() throws Exception {
        var newLogBookResult = testControllerHelperService.getTestLogbook(mockMvc);
        //create supersede
        EntryNotFound exception = assertThrows(
                EntryNotFound.class,
                () -> testControllerHelperService.createNewSupersedeLog(
                        mockMvc,
                        status().is4xxClientError(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        "not found superseded log",
                        EntryNewDTO
                                .builder()
                                .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                .text("This is a log for test")
                                .title("A very wonderful supersede log")
                                .build()
                )
        );
        AssertionsForClassTypes.assertThat(exception.getErrorCode()).isEqualTo(-2);
    }

    @Test
    public void createNewSupersedeOfEntryWithFollowUp() throws Exception {
        var newLogBookResult = testControllerHelperService.getTestLogbook(mockMvc);
        ApiResultResponse<String> newLogIDResult =
                assertDoesNotThrow(
                        () ->
                                testControllerHelperService.createNewLog(
                                        mockMvc,
                                        status().isCreated(),
                                        Optional.of(
                                                "user1@slac.stanford.edu"
                                        ),
                                        EntryNewDTO
                                                .builder()
                                                .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                                .text("This is a log for test")
                                                .title("A very wonderful log")
                                                .build()
                                )
                );

        AssertionsForClassTypes.assertThat(newLogIDResult).isNotNull();
        AssertionsForClassTypes.assertThat(newLogIDResult.getErrorCode()).isEqualTo(0);

        // create new followups
        ApiResultResponse<String> newFollowUpsLogIDResult = assertDoesNotThrow(
                () -> testControllerHelperService.createNewFollowUpLog(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        newLogIDResult.getPayload(),
                        EntryNewDTO
                                .builder()
                                .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                .text("This is a followup for entry %s".formatted(newLogIDResult.getPayload()))
                                .title("A very wonderful follow up log %s".formatted(newLogIDResult.getPayload()))
                                .build()
                )
        );
        AssertionsForClassTypes.assertThat(newFollowUpsLogIDResult).isNotNull();
        AssertionsForClassTypes.assertThat(newFollowUpsLogIDResult.getErrorCode()).isEqualTo(0);

        // create supersede of followed up logs
        ApiResultResponse<String> newSupersedeLogIDResult = assertDoesNotThrow(
                () -> testControllerHelperService.createNewSupersedeLog(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        newLogIDResult.getPayload(),
                        EntryNewDTO
                                .builder()
                                .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                .text("This is a log for test")
                                .title("A very wonderful supersede log")
                                .build()
                )
        );
        AssertionsForClassTypes.assertThat(newSupersedeLogIDResult).isNotNull();
        AssertionsForClassTypes.assertThat(newSupersedeLogIDResult.getErrorCode()).isEqualTo(0);

        //check old log for supersede info
        ApiResultResponse<EntryDTO> supersededFull = assertDoesNotThrow(
                () ->
                        testControllerHelperService.getFullLog(
                                mockMvc,
                                status().isOk(),
                                Optional.of(
                                        "user1@slac.stanford.edu"
                                ),
                                newLogIDResult.getPayload(),
                                true,
                                false,
                                false,
                                false,
                                false,
                                true
                        )
        );

        assertThat(supersededFull.getErrorCode()).isEqualTo(0);
        assertThat(supersededFull.getPayload().supersededBy().id()).isEqualTo(newSupersedeLogIDResult.getPayload());
        assertThat(supersededFull.getPayload().followUps()).isNotNull().isNotEmpty();

        //check new supersede log and check follow-ups
        ApiResultResponse<EntryDTO> supersedeFull = assertDoesNotThrow(
                () ->
                        testControllerHelperService.getFullLog(
                                mockMvc,
                                status().isOk(),
                                Optional.of(
                                        "user1@slac.stanford.edu"
                                ),
                                newSupersedeLogIDResult.getPayload(),
                                true,
                                false,
                                false,
                                false,
                                false,
                                true
                        )
        );

        assertThat(supersedeFull.getErrorCode()).isEqualTo(0);
        assertThat(supersedeFull.getPayload().supersededBy()).isNull();

        assertThat(
                supersedeFull.getPayload().followUps()
        ).isEqualTo(
                supersededFull.getPayload().followUps()
        );
    }

    @Test
    public void getLogHistoryAndFollowingLog() throws Exception {
        var newLogBookResult = testControllerHelperService.getTestLogbook(mockMvc);
        ApiResultResponse<String> newLogIDResult =
                assertDoesNotThrow(
                        () ->
                                testControllerHelperService.createNewLog(
                                        mockMvc,
                                        status().isCreated(),
                                        Optional.of(
                                                "user1@slac.stanford.edu"
                                        ),
                                        EntryNewDTO
                                                .builder()
                                                .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                                .text("This is a log for test")
                                                .title("A very wonderful log")
                                                .build()
                                )
                );

        AssertionsForClassTypes.assertThat(newLogIDResult).isNotNull();
        AssertionsForClassTypes.assertThat(newLogIDResult.getErrorCode()).isEqualTo(0);
        //create supersede
        ApiResultResponse<String> newSupersedeLogIDResult1 = assertDoesNotThrow(
                () -> testControllerHelperService.createNewSupersedeLog(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        newLogIDResult.getPayload(),
                        EntryNewDTO
                                .builder()
                                .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                .text("This is a log for test")
                                .title("A very wonderful supersede log")
                                .build()
                )
        );

        //check old log for supersede info
        ApiResultResponse<String> newSupersedeLogIDResult2 = assertDoesNotThrow(
                () -> testControllerHelperService.createNewSupersedeLog(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        newSupersedeLogIDResult1.getPayload(),
                        EntryNewDTO
                                .builder()
                                .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                .text("This is a log for test")
                                .title("A very wonderful supersede log of supersede")
                                .build()
                )
        );

        //return full log with only history
        ApiResultResponse<EntryDTO> fullLog = assertDoesNotThrow(
                () -> testControllerHelperService.getFullLog(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        newSupersedeLogIDResult2.getPayload(),
                        false,
                        false,
                        true
                )
        );

        assertThat(fullLog.getErrorCode()).isEqualTo(0);
        assertThat(fullLog.getPayload().history()).extracting("id").containsExactly(
                newSupersedeLogIDResult1.getPayload(),
                newLogIDResult.getPayload()
        );
    }


    @Test
    public void createNewFollowUpLogsAndFetch() throws Exception {
        var newLogBookResult = testControllerHelperService.getTestLogbook(mockMvc);
        ApiResultResponse<String> newLogIDResult =
                assertDoesNotThrow(
                        () ->
                                testControllerHelperService.createNewLog(
                                        mockMvc,
                                        status().isCreated(),
                                        Optional.of(
                                                "user1@slac.stanford.edu"
                                        ),
                                        EntryNewDTO
                                                .builder()
                                                .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                                .text("This is a log for test")
                                                .title("A very wonderful log")
                                                .build()
                                )
                );

        AssertionsForClassTypes.assertThat(newLogIDResult).isNotNull();
        AssertionsForClassTypes.assertThat(newLogIDResult.getErrorCode()).isEqualTo(0);

        //create follow-up
        ApiResultResponse<String> newFULogIDOneResult = assertDoesNotThrow(
                () -> testControllerHelperService.createNewFollowUpLog(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        newLogIDResult.getPayload(),
                        EntryNewDTO
                                .builder()
                                .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                .text("This is a log for test")
                                .title("A very wonderful followup log one")
                                .build()
                )
        );

        //create follow-up
        ApiResultResponse<String> newFULogIDTwoResult = assertDoesNotThrow(
                () -> testControllerHelperService.createNewFollowUpLog(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        newLogIDResult.getPayload(),
                        EntryNewDTO
                                .builder()
                                .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                .text("This is a log for test")
                                .title("A very wonderful followup log two")
                                .build()
                )
        );

        ApiResultResponse<List<EntrySummaryDTO>> foundFollowUp = assertDoesNotThrow(
                () -> testControllerHelperService.getAllFollowUpLog(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        newLogIDResult.getPayload()
                )
        );
        assertThat(foundFollowUp.getErrorCode()).isEqualTo(0);
        assertThat(foundFollowUp.getPayload().size()).isEqualTo(2);

        //get full log without followUPs
        ApiResultResponse<EntryDTO> fullLog = assertDoesNotThrow(
                () -> testControllerHelperService.getFullLog(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        newLogIDResult.getPayload(),
                        false
                )
        );

        assertThat(fullLog.getErrorCode()).isEqualTo(0);
        assertThat(fullLog.getPayload().followUps()).isNull();

        //get full log without followUPs
        ApiResultResponse<EntryDTO> fullLogWitFollowUps = assertDoesNotThrow(
                () -> testControllerHelperService.getFullLog(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        newLogIDResult.getPayload(),
                        true
                )
        );

        assertThat(fullLogWitFollowUps.getErrorCode()).isEqualTo(0);
        assertThat(fullLogWitFollowUps.getPayload().followUps()).isNotNull();
        assertThat(fullLogWitFollowUps.getPayload().followUps().size()).isEqualTo(2);

        // check for full log with the following up one
        ApiResultResponse<EntryDTO> fullLogWithFollowingUp = assertDoesNotThrow(
                () -> testControllerHelperService.getFullLog(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        newFULogIDOneResult.getPayload(),
                        false,
                        true,
                        false
                )
        );

        assertThat(fullLogWithFollowingUp.getErrorCode()).isEqualTo(0);
        assertThat(fullLogWithFollowingUp.getPayload().followingUp()).isNotNull();
        assertThat(fullLogWithFollowingUp.getPayload().followingUp().id()).isEqualTo(newLogIDResult.getPayload());
    }


    @Test
    public void searchWithAnchor() {
        var newLogBookResult = testControllerHelperService.getTestLogbook(mockMvc);
        // create some data
        for (int idx = 0; idx < 100; idx++) {
            int finalIdx = idx;
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
                                            .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                            .text("This is a log for test")
                                            .title("A very wonderful log")
                                            .note(String.valueOf(finalIdx))
                                            .build()
                            )
                    );
            AssertionsForClassTypes.assertThat(newLogID).isNotNull();
        }

        // initial search
        ApiResultResponse<List<EntrySummaryDTO>> firstPageResult = assertDoesNotThrow(
                () -> testControllerHelperService.submitSearchByGetWithAnchor(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
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
        AssertionsForInterfaceTypes.assertThat(firstPageResult.getErrorCode()).isEqualTo(0);
        List<EntrySummaryDTO> firstPage = firstPageResult.getPayload();
        AssertionsForInterfaceTypes.assertThat(firstPage).isNotNull();
        AssertionsForClassTypes.assertThat(firstPage.size()).isEqualTo(10);
        String note = null;
        for (EntrySummaryDTO l :
                firstPage) {
            if (note == null) {
                note = l.note();
                continue;
            }
            AssertionsForClassTypes.assertThat(Integer.valueOf(note)).isGreaterThan(
                    Integer.valueOf(l.note())
            );
            note = l.note();
        }
        var testAnchorLog = firstPage.get(firstPage.size() - 1);
        // load next page
        ApiResultResponse<List<EntrySummaryDTO>> nextPageResult = assertDoesNotThrow(
                () -> testControllerHelperService.submitSearchByGetWithAnchor(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        Optional.of(testAnchorLog.id()),
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
        AssertionsForInterfaceTypes.assertThat(nextPageResult.getErrorCode()).isEqualTo(0);
        List<EntrySummaryDTO> nextPage = nextPageResult.getPayload();
        AssertionsForInterfaceTypes.assertThat(nextPage).isNotNull();
        AssertionsForClassTypes.assertThat(nextPage.size()).isEqualTo(10);
        // check that the first of next page is not the last of the previous
        AssertionsForClassTypes.assertThat(nextPage.get(0).id()).isNotEqualTo(firstPage.get(firstPage.size() - 1).id());

        note = nextPage.get(0).note();
        nextPage.remove(0);
        for (EntrySummaryDTO l :
                nextPage) {
            AssertionsForClassTypes.assertThat(Integer.valueOf(note)).isGreaterThan(
                    Integer.valueOf(l.note())
            );
            note = l.note();
        }

        // now get all the record upside and downside
        ApiResultResponse<List<EntrySummaryDTO>> prevPageByPinResult = assertDoesNotThrow(
                () -> testControllerHelperService.submitSearchByGetWithAnchor(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        Optional.of(testAnchorLog.id()),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(10),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()
                )
        );
        AssertionsForInterfaceTypes.assertThat(prevPageByPinResult.getErrorCode()).isEqualTo(0);
        List<EntrySummaryDTO> prevPageByPin = prevPageByPinResult.getPayload();
        AssertionsForInterfaceTypes.assertThat(prevPageByPin).isNotNull();
        AssertionsForClassTypes.assertThat(prevPageByPin.size()).isEqualTo(10);
        AssertionsForInterfaceTypes.assertThat(prevPageByPin).isEqualTo(firstPage);

        // test prev and next
        ApiResultResponse<List<EntrySummaryDTO>> prevAndNextPageByPinResult = assertDoesNotThrow(
                () -> testControllerHelperService.submitSearchByGetWithAnchor(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        Optional.of(testAnchorLog.id()),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(10),
                        Optional.of(10),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()
                )
        );
        AssertionsForInterfaceTypes.assertThat(prevAndNextPageByPinResult.getErrorCode()).isEqualTo(0);
        List<EntrySummaryDTO> prevAndNextPageByPin = prevAndNextPageByPinResult.getPayload();
        AssertionsForInterfaceTypes.assertThat(prevAndNextPageByPin).isNotNull();
        AssertionsForClassTypes.assertThat(prevAndNextPageByPin.size()).isEqualTo(20);
        AssertionsForInterfaceTypes.assertThat(prevAndNextPageByPin).containsAll(firstPage);
        AssertionsForInterfaceTypes.assertThat(prevAndNextPageByPin).containsAll(nextPage);
    }

    @Test
    public void searchWithTags() {
        ApiResultResponse<LogbookDTO> logbookCreationResult = testControllerHelperService.getTestLogbook(mockMvc);
        // create new logbooks
        // create some data
        String[] tagIds = new String[100];
        for (int idx = 0; idx < 100; idx++) {
            int finalIdx = idx;
            ApiResultResponse<String> newTagID = assertDoesNotThrow(
                    () -> testControllerHelperService.createNewLogbookTags(
                            mockMvc,
                            status().isCreated(),
                            Optional.of(
                                    "user1@slac.stanford.edu"
                            ),
                            logbookCreationResult.getPayload().id(),
                            NewTagDTO
                                    .builder()
                                    .name(String.valueOf(finalIdx))
                                    .build()
                    ));
            assertThat(newTagID).isNotNull();
            assertThat(newTagID.getErrorCode()).isEqualTo(0);

            newTagID = assertDoesNotThrow(
                    () -> testControllerHelperService.createNewLogbookTags(
                            mockMvc,
                            status().isCreated(),
                            Optional.of(
                                    "user1@slac.stanford.edu"
                            ),
                            logbookCreationResult.getPayload().id(),
                            NewTagDTO
                                    .builder()
                                    .name(String.valueOf("tags-" + finalIdx))
                                    .build()
                    ));
            assertThat(newTagID).isNotNull();
            assertThat(newTagID.getErrorCode()).isEqualTo(0);
            tagIds[idx] = newTagID.getPayload();
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
                                            .logbooks(Set.of(logbookCreationResult.getPayload().id()))
                                            .text("This is a log for test")
                                            .title("A very wonderful log")
                                            .note(String.valueOf(finalIdx))
                                            .tags(Set.of(tagIds[finalIdx]))
                                            .build()
                            )
                    );
            AssertionsForClassTypes.assertThat(newLogID).isNotNull();
        }

        // initial search
        ApiResultResponse<List<EntrySummaryDTO>> findTags = assertDoesNotThrow(
                () -> testControllerHelperService.submitSearchByGetWithAnchor(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(10),
                        Optional.empty(),
                        Optional.of(List.of(tagIds[99], tagIds[49], tagIds[0])),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()
                )
        );
        assertThat(findTags).isNotNull();
        assertThat(findTags.getPayload().size()).isEqualTo(3);
        assertThat(findTags.getPayload().get(0).tags()).extracting("name").contains("tags-99");
        assertThat(findTags.getPayload().get(1).tags()).extracting("name").contains("tags-49");
        assertThat(findTags.getPayload().get(2).tags()).extracting("name").contains("tags-0");
    }

    @Test
    public void searchWithTagsAllInclueded() {
        ApiResultResponse<LogbookDTO> logbookCreationResult = testControllerHelperService.getTestLogbook(mockMvc);
        ApiResultResponse<String> newTagIDA = assertDoesNotThrow(
                () -> testControllerHelperService.createNewLogbookTags(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        logbookCreationResult.getPayload().id(),
                        NewTagDTO
                                .builder()
                                .name("tag-a")
                                .build()
                ));
        assertThat(newTagIDA).isNotNull();
        assertThat(newTagIDA.getErrorCode()).isEqualTo(0);
        ApiResultResponse<String> newTagIDB = assertDoesNotThrow(
                () -> testControllerHelperService.createNewLogbookTags(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        logbookCreationResult.getPayload().id(),
                        NewTagDTO
                                .builder()
                                .name("tag-b")
                                .build()
                ));
        assertThat(newTagIDB).isNotNull();
        assertThat(newTagIDB.getErrorCode()).isEqualTo(0);

        ApiResultResponse<String> newLogIDWithMoreTag =
                assertDoesNotThrow(
                        () -> testControllerHelperService.createNewLog(
                                mockMvc,
                                status().isCreated(),
                                Optional.of(
                                        "user1@slac.stanford.edu"
                                ),
                                EntryNewDTO
                                        .builder()
                                        .logbooks(Set.of(logbookCreationResult.getPayload().id()))
                                        .text("This is a log for test")
                                        .title("A very wonderful log with two tag")
                                        .tags(Set.of(newTagIDA.getPayload(), newTagIDB.getPayload()))
                                        .build()
                        )
                );
        assertThat(newLogIDWithMoreTag).isNotNull();
        assertThat(newLogIDWithMoreTag.getErrorCode()).isEqualTo(0);

        ApiResultResponse<String> newLogIDWithTagA =
                assertDoesNotThrow(
                        () -> testControllerHelperService.createNewLog(
                                mockMvc,
                                status().isCreated(),
                                Optional.of(
                                        "user1@slac.stanford.edu"
                                ),
                                EntryNewDTO
                                        .builder()
                                        .logbooks(Set.of(logbookCreationResult.getPayload().id()))
                                        .text("This is a log for test")
                                        .title("A very wonderful log with one tag")
                                        .tags(Set.of(newTagIDA.getPayload()))
                                        .build()
                        )
                );
        assertThat(newLogIDWithMoreTag).isNotNull();
        assertThat(newLogIDWithMoreTag.getErrorCode()).isEqualTo(0);
        // fined entry with all the tags
        ApiResultResponse<List<EntrySummaryDTO>> findTags = assertDoesNotThrow(
                () -> testControllerHelperService.submitSearchByGetWithAnchor(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(10),
                        Optional.empty(),
                        Optional.of(List.of(newTagIDA.getPayload(), newTagIDB.getPayload())),
                        Optional.of(true),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()
                )
        );
        assertThat(findTags).isNotNull();
        assertThat(findTags.getPayload().size()).isEqualTo(1);
        assertThat(findTags.getPayload().get(0).tags()).hasSize(2);

        //find entry any tags
        findTags = assertDoesNotThrow(
                () -> testControllerHelperService.submitSearchByGetWithAnchor(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(10),
                        Optional.empty(),
                        Optional.of(List.of(newTagIDA.getPayload(), newTagIDB.getPayload())),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()
                )
        );
        assertThat(findTags).isNotNull();
        assertThat(findTags.getPayload().size()).isEqualTo(2);
    }

    @Test
    public void searchWithText() {
        var newLogBookResult = testControllerHelperService.getTestLogbook(mockMvc);
        // create some data
        for (int idx = 0; idx < 100; idx++) {
            int finalIdx = idx;
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
                                            .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                            .text("This is a log for test")
                                            .note(String.valueOf(finalIdx))
                                            .title("A very wonderful log for index=" + finalIdx)
                                            .build()
                            )
                    );
            AssertionsForClassTypes.assertThat(newLogID).isNotNull();
        }

        // initial search
        ApiResultResponse<List<EntrySummaryDTO>> findTags = assertDoesNotThrow(
                () -> testControllerHelperService.submitSearchByGetWithAnchor(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(10),
                        Optional.of("index=0"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()
                )
        );
        assertThat(findTags).isNotNull();
        assertThat(findTags.getPayload().size()).isNotEqualTo(0);
    }

    @Test
    public void createLogWithTagFailWithNoSave() {
        var newLogBookResult = testControllerHelperService.getTestLogbook(mockMvc);
        TagNotFound tagNotFound =
                assertThrows(
                        TagNotFound.class,
                        () ->
                                testControllerHelperService.createNewLog(
                                        mockMvc,
                                        status().is4xxClientError(),
                                        Optional.of(
                                                "user1@slac.stanford.edu"
                                        ),
                                        EntryNewDTO
                                                .builder()
                                                .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                                .text("This is a log for test")
                                                .title("A very wonderful log")
                                                .tags(Set.of("tag-1", "tag-2"))
                                                .build()
                                )
                );
        AssertionsForClassTypes.assertThat(tagNotFound.getErrorCode()).isEqualTo(-4);
    }

    @Test
    public void createLogWithTagOK() {
        ApiResultResponse<LogbookDTO> logbookCreationResult = testControllerHelperService.getTestLogbook(mockMvc);
        ApiResultResponse<String> tag01Id =
                assertDoesNotThrow(
                        () ->
                                testControllerHelperService.createNewLogbookTags(
                                        mockMvc,
                                        status().isCreated(),
                                        Optional.of(
                                                "user1@slac.stanford.edu"
                                        ),
                                        logbookCreationResult.getPayload().id(),
                                        NewTagDTO
                                                .builder()
                                                .name("tag-1")
                                                .build()
                                )
                );
        ApiResultResponse<String> tag02Id =
                assertDoesNotThrow(
                        () ->
                                testControllerHelperService.createNewLogbookTags(
                                        mockMvc,
                                        status().isCreated(),
                                        Optional.of(
                                                "user1@slac.stanford.edu"
                                        ),
                                        logbookCreationResult.getPayload().id(),
                                        NewTagDTO
                                                .builder()
                                                .name("tag-2")
                                                .build()
                                )
                );
        ApiResultResponse<String> logID =
                assertDoesNotThrow(
                        () ->
                                testControllerHelperService.createNewLog(
                                        mockMvc,
                                        status().isCreated(),
                                        Optional.of(
                                                "user1@slac.stanford.edu"
                                        ),
                                        EntryNewDTO
                                                .builder()
                                                .logbooks(Set.of(logbookCreationResult.getPayload().id()))
                                                .text("This is a log for test")
                                                .title("A very wonderful log")
                                                .tags(Set.of(tag01Id.getPayload(), tag02Id.getPayload()))
                                                .build()
                                )
                );
        AssertionsForClassTypes.assertThat(logID.getErrorCode()).isEqualTo(0);
    }

    @Test
    public void getAllTags() {
        ApiResultResponse<LogbookDTO> logbookCreationResult = testControllerHelperService.getTestLogbook(mockMvc);
        ApiResultResponse<String> tag01Id =
                assertDoesNotThrow(
                        () ->
                                testControllerHelperService.createNewLogbookTags(
                                        mockMvc,
                                        status().isCreated(),
                                        Optional.of(
                                                "user1@slac.stanford.edu"
                                        ),
                                        logbookCreationResult.getPayload().id(),
                                        NewTagDTO
                                                .builder()
                                                .name("tag-1")
                                                .build()
                                )
                );
        ApiResultResponse<String> tag02Id =
                assertDoesNotThrow(
                        () ->
                                testControllerHelperService.createNewLogbookTags(
                                        mockMvc,
                                        status().isCreated(),
                                        Optional.of(
                                                "user1@slac.stanford.edu"
                                        ),
                                        logbookCreationResult.getPayload().id(),
                                        NewTagDTO
                                                .builder()
                                                .name("tag-2")
                                                .build()
                                )
                );
        ApiResultResponse<List<TagDTO>> allTags =
                assertDoesNotThrow(
                        () ->
                                testControllerHelperService.getLogbookTags(
                                        mockMvc,
                                        status().isOk(),
                                        Optional.of(
                                                "user1@slac.stanford.edu"
                                        ),
                                        logbookCreationResult.getPayload().id()
                                )
                );
        AssertionsForInterfaceTypes.assertThat(
                        allTags.getPayload()
                )
                .extracting("name")
                .containsAll(
                        List.of("tag-1", "tag-2")
                );
    }

    @Test
    public void searchWithAnchorUsingLoggedAtInsteadEventAt() {
        var newLogBookResult = testControllerHelperService.getTestLogbook(mockMvc);
        // create some data
        for (int idx = 0; idx < 100; idx++) {
            int finalIdx = idx;
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
                                            .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                            .text("This is a log for test")
                                            .title("A very wonderful log")
                                            .note(String.valueOf(finalIdx))
                                            .eventAt(
                                                    LocalDateTime.now()
                                                            .minusDays(finalIdx)
                                            )
                                            .build()
                            )
                    );
            AssertionsForClassTypes.assertThat(newLogID).isNotNull();
        }

        // initial search
        ApiResultResponse<List<EntrySummaryDTO>> firstPageResult = assertDoesNotThrow(
                () -> testControllerHelperService.submitSearchByGetWithAnchor(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
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
                        Optional.of(true),
                        Optional.empty()
                )
        );
        AssertionsForInterfaceTypes.assertThat(firstPageResult.getErrorCode()).isEqualTo(0);
        List<EntrySummaryDTO> firstPage = firstPageResult.getPayload();
        AssertionsForInterfaceTypes.assertThat(firstPage).isNotNull();
        AssertionsForClassTypes.assertThat(firstPage.size()).isEqualTo(10);
        String note = null;
        for (EntrySummaryDTO l :
                firstPage) {
            if (note == null) {
                note = l.note();
                continue;
            }
            AssertionsForClassTypes.assertThat(Integer.valueOf(note)).isGreaterThan(
                    Integer.valueOf(l.note())
            );
            note = l.note();
        }
        var testAnchorLog = firstPage.get(firstPage.size() - 1);
        // load next page
        ApiResultResponse<List<EntrySummaryDTO>> nextPageResult = assertDoesNotThrow(
                () -> testControllerHelperService.submitSearchByGetWithAnchor(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        Optional.of(testAnchorLog.id()),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(10),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(true),
                        Optional.empty()
                )
        );
        AssertionsForInterfaceTypes.assertThat(nextPageResult.getErrorCode()).isEqualTo(0);
        List<EntrySummaryDTO> nextPage = nextPageResult.getPayload();
        AssertionsForInterfaceTypes.assertThat(nextPage).isNotNull();
        AssertionsForClassTypes.assertThat(nextPage.size()).isEqualTo(10);
        // check that the first of next page is not the last of the previous
        AssertionsForClassTypes.assertThat(nextPage.get(0).id()).isNotEqualTo(firstPage.get(firstPage.size() - 1).id());

        note = nextPage.get(0).note();
        nextPage.remove(0);
        for (EntrySummaryDTO l :
                nextPage) {
            AssertionsForClassTypes.assertThat(Integer.valueOf(note)).isGreaterThan(
                    Integer.valueOf(l.note())
            );
            note = l.note();
        }

        // now get all the record upside and downside
        ApiResultResponse<List<EntrySummaryDTO>> prevPageByPinResult = assertDoesNotThrow(
                () -> testControllerHelperService.submitSearchByGetWithAnchor(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        Optional.of(testAnchorLog.id()),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(10),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(true),
                        Optional.empty()
                )
        );
        AssertionsForInterfaceTypes.assertThat(prevPageByPinResult.getErrorCode()).isEqualTo(0);
        List<EntrySummaryDTO> prevPageByPin = prevPageByPinResult.getPayload();
        AssertionsForInterfaceTypes.assertThat(prevPageByPin).isNotNull();
        AssertionsForClassTypes.assertThat(prevPageByPin.size()).isEqualTo(10);
        AssertionsForInterfaceTypes.assertThat(prevPageByPin).isEqualTo(firstPage);

        // test prev and next
        ApiResultResponse<List<EntrySummaryDTO>> prevAndNextPageByPinResult = assertDoesNotThrow(
                () -> testControllerHelperService.submitSearchByGetWithAnchor(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        Optional.of(testAnchorLog.id()),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(10),
                        Optional.of(10),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(true),
                        Optional.empty()
                )
        );
        AssertionsForInterfaceTypes.assertThat(prevAndNextPageByPinResult.getErrorCode()).isEqualTo(0);
        List<EntrySummaryDTO> prevAndNextPageByPin = prevAndNextPageByPinResult.getPayload();
        AssertionsForInterfaceTypes.assertThat(prevAndNextPageByPin).isNotNull();
        AssertionsForClassTypes.assertThat(prevAndNextPageByPin.size()).isEqualTo(20);
        AssertionsForInterfaceTypes.assertThat(prevAndNextPageByPin).containsAll(firstPage);
        AssertionsForInterfaceTypes.assertThat(prevAndNextPageByPin).containsAll(nextPage);
    }

    @Test
    public void searchWithAnchorUsingReversedEventAtAndDefaultOrder() {
        var newLogBookResult = testControllerHelperService.getTestLogbook(mockMvc);
        // create some data
        for (int idx = 0; idx < 100; idx++) {
            int finalIdx = idx;
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
                                            .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                            .text("This is a log for test")
                                            .title("A very wonderful log")
                                            .note(String.valueOf(finalIdx))
                                            .eventAt(
                                                    LocalDateTime.now()
                                                            .minusDays(finalIdx)
                                            )
                                            .build()
                            )
                    );
            AssertionsForClassTypes.assertThat(newLogID).isNotNull();
        }

        // initial search
        ApiResultResponse<List<EntrySummaryDTO>> firstPageResult = assertDoesNotThrow(
                () -> testControllerHelperService.submitSearchByGetWithAnchor(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
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
        AssertionsForInterfaceTypes.assertThat(firstPageResult.getErrorCode()).isEqualTo(0);
        List<EntrySummaryDTO> firstPage = firstPageResult.getPayload();
        AssertionsForInterfaceTypes.assertThat(firstPage).isNotNull();
        AssertionsForClassTypes.assertThat(firstPage.size()).isEqualTo(10);
        String note = null;
        for (EntrySummaryDTO l :
                firstPage) {
            if (note == null) {
                note = l.note();
                continue;
            }
            AssertionsForClassTypes.assertThat(Integer.valueOf(note)).isLessThan(
                    Integer.valueOf(l.note())
            );
            note = l.note();
        }
        var testAnchorLog = firstPage.get(firstPage.size() - 1);
        // load next page
        ApiResultResponse<List<EntrySummaryDTO>> nextPageResult = assertDoesNotThrow(
                () -> testControllerHelperService.submitSearchByGetWithAnchor(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        Optional.of(testAnchorLog.id()),
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
        AssertionsForInterfaceTypes.assertThat(nextPageResult.getErrorCode()).isEqualTo(0);
        List<EntrySummaryDTO> nextPage = nextPageResult.getPayload();
        AssertionsForInterfaceTypes.assertThat(nextPage).isNotNull();
        AssertionsForClassTypes.assertThat(nextPage.size()).isEqualTo(10);
        // check that the first of next page is not the last of the previous
        AssertionsForClassTypes.assertThat(nextPage.get(0).id()).isNotEqualTo(firstPage.get(firstPage.size() - 1).id());

        note = nextPage.get(0).note();
        nextPage.remove(0);
        for (EntrySummaryDTO l :
                nextPage) {
            AssertionsForClassTypes.assertThat(Integer.valueOf(note)).isLessThan(
                    Integer.valueOf(l.note())
            );
            note = l.note();
        }

        // now get all the record upside and downside
        ApiResultResponse<List<EntrySummaryDTO>> prevPageByPinResult = assertDoesNotThrow(
                () -> testControllerHelperService.submitSearchByGetWithAnchor(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        Optional.of(testAnchorLog.id()),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(10),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()
                )
        );
        AssertionsForInterfaceTypes.assertThat(prevPageByPinResult.getErrorCode()).isEqualTo(0);
        List<EntrySummaryDTO> prevPageByPin = prevPageByPinResult.getPayload();
        AssertionsForInterfaceTypes.assertThat(prevPageByPin).isNotNull();
        AssertionsForClassTypes.assertThat(prevPageByPin.size()).isEqualTo(10);
        AssertionsForInterfaceTypes.assertThat(prevPageByPin).isEqualTo(firstPage);

        // test prev and next
        ApiResultResponse<List<EntrySummaryDTO>> prevAndNextPageByPinResult = assertDoesNotThrow(
                () -> testControllerHelperService.submitSearchByGetWithAnchor(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        Optional.of(testAnchorLog.id()),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(10),
                        Optional.of(10),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()
                )
        );
        AssertionsForInterfaceTypes.assertThat(prevAndNextPageByPinResult.getErrorCode()).isEqualTo(0);
        List<EntrySummaryDTO> prevAndNextPageByPin = prevAndNextPageByPinResult.getPayload();
        AssertionsForInterfaceTypes.assertThat(prevAndNextPageByPin).isNotNull();
        AssertionsForClassTypes.assertThat(prevAndNextPageByPin.size()).isEqualTo(20);
        AssertionsForInterfaceTypes.assertThat(prevAndNextPageByPin).containsAll(firstPage);
        AssertionsForInterfaceTypes.assertThat(prevAndNextPageByPin).containsAll(nextPage);
    }


    @Test
    public void searchWithAnchorUsingReversedEventAtAndDefaultOrderWithDateLimit() {
        LocalDateTime now = LocalDateTime.now();
        var newLogBookResult = testControllerHelperService.getTestLogbook(mockMvc);
        // create some data
        for (int idx = 0; idx < 100; idx++) {
            int finalIdx = idx;
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
                                            .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                            .text("This is a log for test")
                                            .title("A very wonderful log")
                                            .note(String.valueOf(finalIdx))
                                            .eventAt(
                                                    now.minusDays(finalIdx)
                                            )
                                            .build()
                            )
                    );
            AssertionsForClassTypes.assertThat(newLogID).isNotNull();
        }

        // initial with past limit
        ApiResultResponse<List<EntrySummaryDTO>> firstPageResult = assertDoesNotThrow(
                () -> testControllerHelperService.submitSearchByGetWithAnchor(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        Optional.empty(),
                        Optional.of(now.minusDays(10)),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(20),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()
                )
        );
        AssertionsForInterfaceTypes.assertThat(firstPageResult.getErrorCode()).isEqualTo(0);
        List<EntrySummaryDTO> firstPage = firstPageResult.getPayload();
        AssertionsForInterfaceTypes.assertThat(firstPage).isNotNull();
        AssertionsForClassTypes.assertThat(firstPage.size()).isEqualTo(11);
        AssertionsForClassTypes.assertThat(firstPage.get(0).eventAt().getDayOfMonth()).isEqualTo(now.getDayOfMonth());
        AssertionsForClassTypes.assertThat(firstPage.get(10).eventAt().getDayOfMonth()).isEqualTo(now.minusDays(10).getDayOfMonth());


        // end and start limit
        ApiResultResponse<List<EntrySummaryDTO>> secondPageResult = assertDoesNotThrow(
                () -> testControllerHelperService.submitSearchByGetWithAnchor(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        Optional.empty(),
                        Optional.of(now.minusDays(10)),
                        Optional.of(now.minusDays(5)),
                        Optional.empty(),
                        Optional.of(20),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()
                )
        );
        AssertionsForInterfaceTypes.assertThat(secondPageResult.getErrorCode()).isEqualTo(0);
        List<EntrySummaryDTO> secondPage = secondPageResult.getPayload();
        AssertionsForInterfaceTypes.assertThat(secondPage).isNotNull();
        AssertionsForClassTypes.assertThat(secondPage.size()).isEqualTo(6);
        AssertionsForClassTypes.assertThat(secondPage.get(0).eventAt().getDayOfMonth()).isEqualTo(now.minusDays(5).getDayOfMonth());
        AssertionsForClassTypes.assertThat(secondPage.get(5).eventAt().getDayOfMonth()).isEqualTo(now.minusDays(10).getDayOfMonth());
    }

    @Test
    public void searchWithAnchorUsingReversedEventAtAndDefaultOrderWithDateLimitAndPaging() {
        LocalDateTime now = LocalDateTime.now();
        var newLogBookResult = testControllerHelperService.getTestLogbook(mockMvc);
        // create some data
        for (int idx = 0; idx < 100; idx++) {
            int finalIdx = idx;
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
                                            .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                            .text("This is a log for test")
                                            .title("A very wonderful log")
                                            .note(String.valueOf(finalIdx))
                                            .eventAt(
                                                    now.minusDays(finalIdx)
                                            )
                                            .build()
                            )
                    );
            AssertionsForClassTypes.assertThat(newLogID).isNotNull();
        }

        boolean getNextPage = true;
        EntrySummaryDTO anchorEntry = null;
        EntrySummaryDTO firstEntryFirstPage = null;
        EntrySummaryDTO lastEntryLastPage = null;
        while (getNextPage) {
            // initial with past limit
            EntrySummaryDTO finalAnchorEntry = anchorEntry;
            ApiResultResponse<List<EntrySummaryDTO>> nextPage = assertDoesNotThrow(
                    () -> testControllerHelperService.submitSearchByGetWithAnchor(
                            mockMvc,
                            status().isOk(),
                            Optional.of(
                                    "user1@slac.stanford.edu"
                            ),
                            (finalAnchorEntry == null) ? Optional.empty() : Optional.of(finalAnchorEntry.id()),
                            Optional.of(now.minusDays(50)),
                            Optional.of(now.minusDays(1)),
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
            AssertionsForInterfaceTypes.assertThat(nextPage.getErrorCode()).isEqualTo(0);
            if (firstEntryFirstPage == null) {
                firstEntryFirstPage = nextPage.getPayload().get(0);
            } else if (!nextPage.getPayload().isEmpty()) {
                lastEntryLastPage = nextPage.getPayload().get(nextPage.getPayload().size() - 1);
            }

            if (nextPage.getPayload().size() < 10) {
                getNextPage = false;
            } else {
                anchorEntry = nextPage.getPayload().get(9);
            }
        }
        assertThat(firstEntryFirstPage.eventAt().getDayOfMonth()).isEqualTo(now.minusDays(1).getDayOfMonth());
        assertThat(lastEntryLastPage.eventAt().getDayOfMonth()).isEqualTo(now.minusDays(50).getDayOfMonth());
    }

    @Test
    public void testEntryInTheRightShift() {
        var newLogBookResult = testControllerHelperService.getTestLogbook(mockMvc);
        ApiResultResponse<LogbookDTO> finalNewLogBookResult2 = newLogBookResult;
        ApiResultResponse<Boolean> replacementResult = assertDoesNotThrow(
                () -> testControllerHelperService.updateLogbook(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        finalNewLogBookResult2.getPayload().id(),
                        UpdateLogbookDTO
                                .builder()
                                .name(finalNewLogBookResult2.getPayload().name())
                                .tags(
                                        Collections.emptyList()
                                )
                                .shifts(
                                        List.of(
                                                ShiftDTO.builder()
                                                        .id(null)
                                                        .name("Owl Shift")
                                                        .from(DateUtilities.toUTCString(LocalTime.of(0,0)))
                                                        .to(DateUtilities.toUTCString(LocalTime.of(7,59)))
                                                        .build(),
                                                ShiftDTO.builder()
                                                        .id(null)
                                                        .name("Day Shift")
                                                        .from(DateUtilities.toUTCString(LocalTime.of(8,0)))
                                                        .to(DateUtilities.toUTCString(LocalTime.of(15,59)))
                                                        .build(),
                                                ShiftDTO.builder()
                                                        .id(null)
                                                        .name("Swing Shift")
                                                        .from(DateUtilities.toUTCString(LocalTime.of(16,0)))
                                                        .to(DateUtilities.toUTCString(LocalTime.of(23,59)))
                                                        .build()
                                        )
                                )
                                .build()
                )
        );

        ApiResultResponse<String> newLogID =
                assertDoesNotThrow(
                        () ->
                                testControllerHelperService.createNewLog(
                                        mockMvc,
                                        status().isCreated(),
                                        Optional.of(
                                                "user1@slac.stanford.edu"
                                        ),
                                        EntryNewDTO
                                                .builder()
                                                .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                                .text("This is a log for test")
                                                .title("A very wonderful log")
                                                .eventAt(LocalDateTime.of(2024,1,1, 7,59,30))
                                                .build()
                                )
                );

        AssertionsForClassTypes.assertThat(newLogID).isNotNull();

        ApiResultResponse<EntryDTO> logEntry = assertDoesNotThrow(
                () ->
                        testControllerHelperService.getFullLog(
                                mockMvc,
                                status().isOk(),
                                Optional.of("user1@slac.stanford.edu"),
                                newLogID.getPayload()
                        )
        );
        AssertionsForClassTypes.assertThat(logEntry).isNotNull();
        AssertionsForClassTypes.assertThat(logEntry.getErrorCode()).isEqualTo(0);
        AssertionsForClassTypes.assertThat(logEntry.getPayload().id()).isEqualTo(newLogID.getPayload());
    }

    @Test
    public void findSummaryId() {
        var newLogBookResult = testControllerHelperService.getTestLogbook(mockMvc);
        ApiResultResponse<LogbookDTO> finalNewLogBookResult2 = newLogBookResult;
        ApiResultResponse<Boolean> replacementResult = assertDoesNotThrow(
                () -> testControllerHelperService.updateLogbook(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        finalNewLogBookResult2.getPayload().id(),
                        UpdateLogbookDTO
                                .builder()
                                .name(finalNewLogBookResult2.getPayload().name())
                                .tags(
                                        Collections.emptyList()
                                )
                                .shifts(
                                        List.of(
                                                ShiftDTO.builder()
                                                        .id(null)
                                                        .name("Morning Shift")
                                                        .from("16:09")
                                                        .to("17:09")
                                                        .build()
                                        )
                                )
                                .build()
                )
        );

        ApiResultResponse<LogbookDTO> finalNewLogBookResult = newLogBookResult;
        newLogBookResult = assertDoesNotThrow(
                () -> testControllerHelperService.getLogbookByID(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        finalNewLogBookResult.getPayload().id()
                )
        );

        ApiResultResponse<LogbookDTO> finalNewLogBookResult1 = newLogBookResult;
        ApiResultResponse<String> newLogID =
                assertDoesNotThrow(
                        () ->
                                testControllerHelperService.createNewLog(
                                        mockMvc,
                                        status().isCreated(),
                                        Optional.of(
                                                "user1@slac.stanford.edu"
                                        ),
                                        EntryNewDTO
                                                .builder()
                                                .logbooks(Set.of(finalNewLogBookResult1.getPayload().id()))
                                                .text("This is a log for test")
                                                .title("A very wonderful log")
                                                .summarizes(
                                                        SummarizesDTO
                                                                .builder()
                                                                .shiftId(finalNewLogBookResult1.getPayload().shifts().get(0).id())
                                                                .date(LocalDate.now())
                                                                .build()
                                                )
                                                .build()
                                )
                );

        AssertionsForClassTypes.assertThat(newLogID).isNotNull();
        AssertionsForClassTypes.assertThat(newLogID.getErrorCode()).isEqualTo(0);

        ApiResultResponse<String> foundSummaryID = assertDoesNotThrow(
                () ->
                        testControllerHelperService.findSummaryIdByShiftNameAndDate(
                                mockMvc,
                                status().isOk(),
                                Optional.of("user1@slac.stanford.edu"),
                                finalNewLogBookResult1.getPayload().shifts().get(0).id(),
                                LocalDate.now()
                        )
        );
        AssertionsForClassTypes.assertThat(foundSummaryID).isNotNull();
        AssertionsForClassTypes.assertThat(foundSummaryID.getErrorCode()).isEqualTo(0);
        AssertionsForClassTypes.assertThat(foundSummaryID.getPayload()).isEqualTo(newLogID.getPayload());
    }

    @Test
    public void createNewLogWitReferenceAndFindAllReferenced() throws Exception {
        var newLogBookResult = testControllerHelperService.getTestLogbook(mockMvc);
        // create entry for use as references
        ApiResultResponse<String> newLogID1 =
                assertDoesNotThrow(
                        () ->
                                testControllerHelperService.createNewLog(
                                        mockMvc,
                                        status().isCreated(),
                                        Optional.of(
                                                "user1@slac.stanford.edu"
                                        ),
                                        EntryNewDTO
                                                .builder()
                                                .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                                .text("reference one")
                                                .title("Reference one")
                                                .build()
                                )
                );

        AssertionsForClassTypes.assertThat(newLogID1).isNotNull();
        AssertionsForClassTypes.assertThat(newLogID1.getErrorCode()).isEqualTo(0);
        // create entry for use as references
        ApiResultResponse<String> newLogID2 =
                assertDoesNotThrow(
                        () ->
                                testControllerHelperService.createNewLog(
                                        mockMvc,
                                        status().isCreated(),
                                        Optional.of(
                                                "user1@slac.stanford.edu"
                                        ),
                                        EntryNewDTO
                                                .builder()
                                                .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                                .text("reference two")
                                                .title("Reference two")
                                                .build()
                                )
                );

        AssertionsForClassTypes.assertThat(newLogID2).isNotNull();
        AssertionsForClassTypes.assertThat(newLogID2.getErrorCode()).isEqualTo(0);

        // create entry that reference the two above
        Element fragment = new Element("div");
        fragment.appendElement(ELOG_ENTRY_REF).attr(ELOG_ENTRY_REF_ID, newLogID1.getPayload());
        fragment.appendText("This is the text between two referencer");
        fragment.appendElement(ELOG_ENTRY_REF).attr(ELOG_ENTRY_REF_ID, newLogID2.getPayload());

        ApiResultResponse<String> newLogIDReferencer =
                assertDoesNotThrow(
                        () ->
                                testControllerHelperService.createNewLog(
                                        mockMvc,
                                        status().isCreated(),
                                        Optional.of(
                                                "user1@slac.stanford.edu"
                                        ),
                                        EntryNewDTO
                                                .builder()
                                                .logbooks(Set.of(newLogBookResult.getPayload().id()))
                                                .title("Referencer log")
                                                .text(fragment.html())
                                                .build()
                                )
                );

        AssertionsForClassTypes.assertThat(newLogIDReferencer).isNotNull();
        AssertionsForClassTypes.assertThat(newLogIDReferencer.getErrorCode()).isEqualTo(0);

        ApiResultResponse<List<EntrySummaryDTO>> references =
                assertDoesNotThrow(
                        () ->
                                testControllerHelperService.getReferencesByEntryId(
                                        mockMvc,
                                        status().isOk(),
                                        Optional.of(
                                                "user1@slac.stanford.edu"
                                        ),
                                        newLogIDReferencer.getPayload()
                                )
                );
        assertThat(references).isNotNull();
        assertThat(references.getErrorCode()).isEqualTo(0);
        assertThat(references.getPayload()).hasSize(2);
        assertThat(references.getPayload()).extracting("id").contains(newLogID1.getPayload(), newLogID2.getPayload());
        assertThat(references.getPayload().get(0).referencedBy()).contains(newLogIDReferencer.getPayload());
        assertThat(references.getPayload().get(1).referencedBy()).contains(newLogIDReferencer.getPayload());

        ApiResultResponse<EntryDTO> referencerEntry = assertDoesNotThrow(
                () ->
                        testControllerHelperService.getFullLog(
                                mockMvc,
                                status().isOk(),
                                Optional.of(
                                        "user1@slac.stanford.edu"
                                ),
                                newLogIDReferencer.getPayload(),
                                true,
                                true,
                                true,
                                true,
                                true,
                                false
                        )
        );
        assertThat(referencerEntry).isNotNull();
        assertThat(referencerEntry.getErrorCode()).isEqualTo(0);
        assertThat(referencerEntry.getPayload().referencesInBody()).isTrue();
        assertThat(referencerEntry.getPayload().references()).extracting("id").contains(newLogID1.getPayload(), newLogID2.getPayload());
        assertThat(referencerEntry.getPayload().referencedBy()).isEmpty();


        ApiResultResponse<EntryDTO> referenceOne = assertDoesNotThrow(
                () ->
                        testControllerHelperService.getFullLog(
                                mockMvc,
                                status().isOk(),
                                Optional.of(
                                        "user1@slac.stanford.edu"
                                ),
                                newLogID1.getPayload(),
                                true,
                                true,
                                true,
                                true,
                                true,
                                false
                        )
        );
        assertThat(referenceOne).isNotNull();
        assertThat(referenceOne.getErrorCode()).isEqualTo(0);
        assertThat(referenceOne.getPayload().referencedBy()).extracting("id").contains(newLogIDReferencer.getPayload());


        ApiResultResponse<EntryDTO> referenceTwo = assertDoesNotThrow(
                () ->
                        testControllerHelperService.getFullLog(
                                mockMvc,
                                status().isOk(),
                                Optional.of(
                                        "user1@slac.stanford.edu"
                                ),
                                newLogID2.getPayload(),
                                true,
                                true,
                                true,
                                true,
                                true,
                                false
                        )
        );
        assertThat(referenceTwo).isNotNull();
        assertThat(referenceTwo.getErrorCode()).isEqualTo(0);
        assertThat(referenceTwo.getPayload().referencedBy()).extracting("id").contains(newLogIDReferencer.getPayload());
    }
}
