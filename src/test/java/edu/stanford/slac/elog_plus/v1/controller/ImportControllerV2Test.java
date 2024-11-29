package edu.stanford.slac.elog_plus.v1.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.stanford.slac.ad.eed.baselib.api.v1.dto.ApiResultResponse;
import edu.stanford.slac.ad.eed.baselib.config.AppProperties;
import edu.stanford.slac.ad.eed.baselib.exception.ControllerLogicException;
import edu.stanford.slac.ad.eed.baselib.model.AuthenticationToken;
import edu.stanford.slac.ad.eed.baselib.model.Authorization;
import edu.stanford.slac.ad.eed.baselib.service.AuthService;
import edu.stanford.slac.elog_plus.api.v1.dto.*;
import edu.stanford.slac.elog_plus.model.*;
import edu.stanford.slac.elog_plus.service.LogbookService;
import edu.stanford.slac.elog_plus.service.DocumentGenerationService;
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
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.io.InputStream;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@SpringBootTest()
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(MockitoExtension.class)
@ActiveProfiles({"test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class ImportControllerV2Test {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LogbookService logbookService;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private TestControllerHelperService testControllerHelperService;

    @Autowired
    private DocumentGenerationService documentGenerationService;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private AuthService authService;
    @Autowired
    private ObjectMapper objectMapper = null;

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
    }

    @Test
    public void uploadEntryFailsWithNoEntry() {
        MissingServletRequestPartException exception = assertThrows(
                MissingServletRequestPartException.class,
                () -> testControllerHelperService.importEntryV1(
                        mockMvc, status().is4xxClientError(),
                        Optional.of(
                                "user3@slac.stanford.edu"
                        ),
                        null,
                        new MockMultipartFile[]{}
                )
        );

        assertThat(exception.getMessage()).containsPattern(".*entry.*");
    }

    @Test
    public void uploadEntryWithNoAttachment() {
        var testLogbook = getTestLogbook();
        EntryImportDTO dto = EntryImportDTO
                .builder()
                .title("Sample Title")
                .text("sample text")
                .logbooks(List.of(testLogbook.getPayload().name()))
                .build();

        ApiResultResponse<String> uploadResult = assertDoesNotThrow(
                () -> testControllerHelperService.importEntryV1(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user3@slac.stanford.edu"
                        ),
                        dto,
                        new MockMultipartFile[]{})
        );

        assertThat(uploadResult.getErrorCode()).isEqualTo(0);
        assertThat(uploadResult.getPayload()).isNotEmpty();
    }

    @Test
    public void importEntryWithAttachment() {
        var testLogbook = getTestLogbook();
        EntryImportDTO dto = EntryImportDTO
                .builder()
                .originId("origin-id")
                .title("Sample Title")
                .text("sample text")
                .logbooks(List.of(testLogbook.getPayload().name()))
                .tags(
                        List.of(
                                "Tag OnE"
                        )
                )
                .firstName("First name import")
                .lastName("Last name import")
                .userName("Username name import")
                .eventAt(
                        LocalDateTime.of(
                                2021,
                                1,
                                1,
                                1,
                                1
                        )
                )
                .loggedAt(
                        LocalDateTime.of(
                                2021,
                                2,
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
                    () -> testControllerHelperService.importEntryV1(
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
            assertThat(fullLog.getPayload().tags()).extracting("name").contains("tag-one");
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }


        ControllerLogicException alreadyFound = assertThrows(
                ControllerLogicException.class,
                () -> testControllerHelperService.importEntryV1(
                        mockMvc,
                        status().is5xxServerError(),
                        Optional.of(
                                "user3@slac.stanford.edu"
                        ),
                        EntryImportDTO
                                .builder()
                                .originId("origin-id")
                                .title("Sample Title")
                                .text("sample text")
                                .logbooks(List.of(testLogbook.getPayload().name()))
                                .build()
                )
        );

        assertThat(alreadyFound.getErrorCode()).isEqualTo(-2);
    }

    @Test
    public void importEntityWithGT() {
        var testLogbook = getTestLogbook("mcc", "user1@slac.stanford.edu");
        String json_string = """
                    {
                    "originId": "XYZ_1331857",
                    "logbooks": ["mcc"],
                    "title": "* Re: use html escaped character--&gt; should now work",
                            "text": "i use --&gt; also in the text",
                            "lastName": "abc",
                            "firstName": "deb",
                            "userName": "abc",
                            "tags": ["FACET"],
                    "loggedAt": "2024-11-28T12:06:32",
                            "eventAt": "2024-11-28T12:06:32"
                }""";

        ApiResultResponse<String> uploadResult = assertDoesNotThrow(
                () -> testControllerHelperService.importEntryV1(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user3@slac.stanford.edu"
                        ),
                        objectMapper.readValue(json_string, EntryImportDTO.class)
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
        assertThat(fullLog.getPayload().title()).isEqualTo("* Re: use html escaped character--> should now work");
        assertThat(fullLog.getPayload().text()).contains("-->");

    }

    private ApiResultResponse<LogbookDTO> getTestLogbook() {
        return getTestLogbook("user1@slac.stanford.edu");
    }

    private ApiResultResponse<LogbookDTO> getTestLogbook(String withUserEmail) {
        ApiResultResponse<String> logbookCreationResult = assertDoesNotThrow(
                () -> testControllerHelperService.createNewLogbook(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                withUserEmail
                        ),
                        NewLogbookDTO
                                .builder()
                                .name(UUID.randomUUID().toString())
                                .build()
                ));
        assertThat(logbookCreationResult).isNotNull();
        assertThat(logbookCreationResult.getErrorCode()).isEqualTo(0);
        return assertDoesNotThrow(
                () -> testControllerHelperService.getLogbookByID(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        logbookCreationResult.getPayload()
                )
        );
    }

    private ApiResultResponse<LogbookDTO> getTestLogbook(String logbookName, String withUserEmail) {
        ApiResultResponse<String> logbookCreationResult = assertDoesNotThrow(
                () -> testControllerHelperService.createNewLogbook(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                withUserEmail
                        ),
                        NewLogbookDTO
                                .builder()
                                .name(logbookName)
                                .build()
                ));
        assertThat(logbookCreationResult).isNotNull();
        assertThat(logbookCreationResult.getErrorCode()).isEqualTo(0);
        return assertDoesNotThrow(
                () -> testControllerHelperService.getLogbookByID(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        logbookCreationResult.getPayload()
                )
        );
    }
}
