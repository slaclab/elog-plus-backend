package edu.stanford.slac.elog_plus.v2;

import edu.stanford.slac.ad.eed.baselib.api.v1.dto.ApiResultResponse;
import edu.stanford.slac.ad.eed.baselib.config.AppProperties;
import edu.stanford.slac.ad.eed.baselib.exception.ControllerLogicException;
import edu.stanford.slac.ad.eed.baselib.model.AuthenticationToken;
import edu.stanford.slac.ad.eed.baselib.model.Authorization;
import edu.stanford.slac.ad.eed.baselib.service.AuthService;
import edu.stanford.slac.elog_plus.api.v1.dto.*;
import edu.stanford.slac.elog_plus.api.v2.dto.NewEntryDTO;
import edu.stanford.slac.elog_plus.exception.TagNotFound;
import edu.stanford.slac.elog_plus.model.Attachment;
import edu.stanford.slac.elog_plus.model.Entry;
import edu.stanford.slac.elog_plus.model.Logbook;
import edu.stanford.slac.elog_plus.service.DocumentGenerationService;
import edu.stanford.slac.elog_plus.service.LogbookService;
import edu.stanford.slac.elog_plus.service.SharedUtilityService;
import edu.stanford.slac.elog_plus.v1.controller.TestControllerHelperService;
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
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.*;

import static java.util.Collections.emptyList;
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

    @Autowired
    private SharedUtilityService sharedUtilityService;

    @BeforeEach
    public void preTest() {
        mongoTemplate.remove(new Query(), Entry.class);
        mongoTemplate.remove(new Query(), Attachment.class);
        mongoTemplate.remove(new Query(), Logbook.class);
        mongoTemplate.remove(new Query(), Authorization.class);
        mongoTemplate.remove(new Query(), AuthenticationToken.class);
        appProperties.getRootUserList().clear();
        appProperties.getRootUserList().add("user1@slac.stanford.edu");
        authService.updateRootUser();
        sharedUtilityService.cleanKafkaPreviewTopic();
    }

    @Test
    public void createNewLogEntry() throws Exception {
        var testLogbook = getTestLogbook(
                "Test Logbook",
                "user1@slac.stanford.edu",
                List.of(
                        TagDTO.builder().name("tag-1").description("tag 1 description").build(),
                        TagDTO.builder().name("tag-2").description("tag 2 description").build(),
                        TagDTO.builder().name("tag-3").description("tag 3 description").build()
                ));
        NewEntryDTO dto = NewEntryDTO
                .builder()
                .title("Sample Title")
                .text("sample text")
                .logbooks(Set.of(testLogbook.getPayload().name()))
                .tags(Set.of("tag-1"))
                .eventAt(
                        LocalDateTime.of(
                                2021,
                                1,
                                1,
                                1,
                                1
                        )
                )
                .build();

        try {
            InputStream isPng = documentGenerationService.getTestPng();
            InputStream isJpg = documentGenerationService.getTestJpeg();
            ApiResultResponse<String> uploadResult = assertDoesNotThrow(
                    () -> testControllerHelperService.v2EntriesControllerCreateEntry(
                            mockMvc,
                            status().isCreated(),
                            Optional.of(
                                    "user3@slac.stanford.edu"
                            ),
                            dto,
                            new MockMultipartFile(
                                    "files",
                                    "test.png",
                                    MediaType.IMAGE_PNG_VALUE,
                                    isPng
                            ),
                            new MockMultipartFile(
                                    "files",
                                    "test.jpg",
                                    MediaType.IMAGE_JPEG_VALUE,
                                    isJpg
                            ))
            );

            assertThat(uploadResult).isNotNull();
            assertThat(uploadResult.getErrorCode()).isEqualTo(0);

            ApiResultResponse<EntryDTO> fullLog = assertDoesNotThrow(
                    () -> testControllerHelperService.getFullLog(
                            mockMvc,
                            status().isOk(),
                            Optional.of(
                                    "user1@slac.stanford.edu"
                            ),
                            uploadResult.getPayload()
                    )
            );
            assertThat(fullLog.getPayload().tags()).extracting("name").contains("tag-1");
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void createNewLogEntryOnMultipleLogbook() throws Exception {
        var testLogbookA = getTestLogbook(
                "Test Logbook A",
                "user1@slac.stanford.edu",
                List.of(
                        TagDTO.builder().name("tag-1").description("tag 1 description").build(),
                        TagDTO.builder().name("tag-2").description("tag 2 description").build(),
                        TagDTO.builder().name("tag-3").description("tag 3 description").build()
                ));
        var testLogbookB = getTestLogbook(
                "Test Logbook B",
                "user1@slac.stanford.edu",
                List.of(
                        TagDTO.builder().name("tag-1").description("tag 1 description").build()
                ));
        NewEntryDTO dto = NewEntryDTO
                .builder()
                .title("Sample Title")
                .text("sample text")
                .logbooks(Set.of(testLogbookA.getPayload().name(), testLogbookB.getPayload().name()))
                .tags(Set.of("tag-1"))
                .eventAt(
                        LocalDateTime.of(
                                2021,
                                1,
                                1,
                                1,
                                1
                        )
                )
                .build();

        ApiResultResponse<String> uploadResult = assertDoesNotThrow(
                () -> testControllerHelperService.v2EntriesControllerCreateEntry(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user3@slac.stanford.edu"
                        ),
                        dto
                )
        );

        assertThat(uploadResult).isNotNull();
        assertThat(uploadResult.getErrorCode()).isEqualTo(0);

        ApiResultResponse<EntryDTO> fullLog = assertDoesNotThrow(
                () -> testControllerHelperService.getFullLog(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        uploadResult.getPayload()
                )
        );
        assertThat(fullLog.getPayload().tags()).extracting("name").contains("tag-1");
    }

    @Test
    public void createNewLogEntryOnMultipleLogbookFailsUsingNoFoundTagsOnBoth() throws Exception {
        var testLogbookA = getTestLogbook(
                "Test Logbook A",
                "user1@slac.stanford.edu",
                List.of(
                        TagDTO.builder().name("tag-1").description("tag 1 description").build(),
                        TagDTO.builder().name("tag-2").description("tag 2 description").build(),
                        TagDTO.builder().name("tag-3").description("tag 3 description").build()
                ));
        var testLogbookB = getTestLogbook(
                "Test Logbook B",
                "user1@slac.stanford.edu",
                List.of(
                        TagDTO.builder().name("tag-1").description("tag 1 description").build()
                ));

        TagNotFound failUsingANotFoundTag = assertThrows(
                TagNotFound.class,
                () -> testControllerHelperService.v2EntriesControllerCreateEntry(
                        mockMvc,
                        status().isNotFound(),
                        Optional.of(
                                "user3@slac.stanford.edu"
                        ),
                        NewEntryDTO
                                .builder()
                                .title("Sample Title")
                                .text("sample text")
                                .logbooks(Set.of(testLogbookA.getPayload().name(), testLogbookB.getPayload().name()))
                                .tags(Set.of("tag-2"))
                                .eventAt(
                                        LocalDateTime.of(
                                                2021,
                                                1,
                                                1,
                                                1,
                                                1
                                        )
                                )
                                .build()
                )
        );

        assertThat(failUsingANotFoundTag).isNotNull();
    }

    /**
     * Create a new logbook for testing
     *
     * @return the logbook created
     */
    private ApiResultResponse<LogbookDTO> getTestLogbook(String logbookName, String withUserEmail, List<TagDTO> tags) {
        ApiResultResponse<String> logbookCreationResult = assertDoesNotThrow(
                () -> testControllerHelperService.createNewLogbook(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(withUserEmail),
                        NewLogbookDTO
                                .builder()
                                .name(logbookName)
                                .writeAll(true)
                                .build()
                ));
        assertThat(logbookCreationResult).isNotNull();
        assertThat(logbookCreationResult.getErrorCode()).isEqualTo(0);

        var lb = assertDoesNotThrow(
                () -> testControllerHelperService.getLogbookByID(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        logbookCreationResult.getPayload()
                )
        );

        // add tags
        ApiResultResponse<Boolean> updateLogbook = assertDoesNotThrow(
                () -> testControllerHelperService.updateLogbook(
                        mockMvc,
                        status().isOk(),
                        Optional.of(withUserEmail),
                        logbookCreationResult.getPayload(),
                        UpdateLogbookDTO
                                .builder()
                                .name(lb.getPayload().name())
                                .writeAll(lb.getPayload().writeAll())
                                .shifts(lb.getPayload().shifts())
                                .readAll(lb.getPayload().readAll())
                                .tags(tags)
                                .build()
                )
        );
        return lb;
    }
}
