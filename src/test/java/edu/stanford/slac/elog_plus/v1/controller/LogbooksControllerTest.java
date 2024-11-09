package edu.stanford.slac.elog_plus.v1.controller;

import edu.stanford.slac.ad.eed.baselib.api.v1.dto.ApiResultResponse;
import edu.stanford.slac.ad.eed.baselib.config.AppProperties;
import edu.stanford.slac.ad.eed.baselib.exception.NotAuthorized;
import edu.stanford.slac.ad.eed.baselib.model.Authorization;
import edu.stanford.slac.ad.eed.baselib.service.AuthService;
import edu.stanford.slac.elog_plus.api.v1.dto.*;
import edu.stanford.slac.elog_plus.exception.LogbookAlreadyExists;
import edu.stanford.slac.elog_plus.exception.TagAlreadyExists;
import edu.stanford.slac.elog_plus.model.Entry;
import edu.stanford.slac.elog_plus.model.Logbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static java.util.Collections.emptyList;
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
public class LogbooksControllerTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AppProperties appProperties;
    @Autowired
    private AuthService authService;
    @Autowired
    private MongoTemplate mongoTemplate;
    @Autowired
    private TestControllerHelperService testControllerHelperService;
    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    public void preTest() {
        mongoTemplate.remove(new Query(), Logbook.class);
        mongoTemplate.remove(new Query(), Entry.class);
        //reset authorizations
        mongoTemplate.remove(new Query(), Authorization.class);
        appProperties.getRootUserList().clear();
        appProperties.getRootUserList().add("user1@slac.stanford.edu");
        authService.updateRootUser();

        cacheManager.getCacheNames().forEach(cacheName -> Objects.requireNonNull(cacheManager.getCache(cacheName)).clear());
    }

    @Test
    public void createNewLogbookAndGet() throws Exception {
        ApiResultResponse<String> creationResult = assertDoesNotThrow(
                () -> testControllerHelperService.createNewLogbook(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        NewLogbookDTO.builder()
                                .name("new-logbooks")
                                .build()
                )
        );

        assertThat(creationResult).isNotNull();
        assertThat(creationResult.getErrorCode()).isEqualTo(0);
        assertThat(creationResult.getPayload()).isNotEmpty();

        ApiResultResponse<LogbookDTO> getLogResult = assertDoesNotThrow(
                () -> testControllerHelperService.getLogbookByID(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        creationResult.getPayload()
                )
        );
        assertThat(getLogResult).isNotNull();
        assertThat(getLogResult.getPayload().id()).isEqualTo(creationResult.getPayload());
        assertThat(getLogResult.getPayload().name()).isEqualTo("new-logbooks");
        assertThat(getLogResult.getPayload().tags()).isEmpty();
        assertThat(getLogResult.getPayload().shifts()).isEmpty();
        assertThat(getLogResult.getPayload().readAll()).isFalse();
        assertThat(getLogResult.getPayload().writeAll()).isFalse();
    }

    @Test
    public void createNewLogbookFailWithNoRootUser() throws Exception {
        NotAuthorized notAuthenticatedForCreateLogbook = assertThrows(
                NotAuthorized.class,
                () -> testControllerHelperService.createNewLogbook(
                        mockMvc,
                        status().isUnauthorized(),
                        Optional.of(
                                "user3@slac.stanford.edu"
                        ),
                        NewLogbookDTO.builder()
                                .name("new-logbooks")
                                .build()
                )
        );
        assertThat(notAuthenticatedForCreateLogbook.getErrorCode()).isEqualTo(-1);
    }

    @Test
    public void failCreatingTwoSameLogbook() {
        ApiResultResponse<String> creationResult = assertDoesNotThrow(
                () -> testControllerHelperService.createNewLogbook(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        NewLogbookDTO.builder()
                                .name("new-logbooks")
                                .build()
                )
        );
        assertThat(creationResult).isNotNull();
        assertThat(creationResult.getErrorCode()).isEqualTo(0);
        assertThat(creationResult.getPayload()).isNotEmpty();

        LogbookAlreadyExists alreadyExistsException = assertThrows(
                LogbookAlreadyExists.class,
                () -> testControllerHelperService.createNewLogbook(
                        mockMvc,
                        status().isConflict(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        NewLogbookDTO.builder()
                                .name("New Logbooks")
                                .build()
                )
        );
        assertThat(alreadyExistsException.getErrorCode()).isEqualTo(-2);
    }

    @Test
    public void createAndGetLogbookTagsWithNoDescription() throws Exception {
        ApiResultResponse<String> creationResult = assertDoesNotThrow(
                () -> testControllerHelperService.createNewLogbook(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        NewLogbookDTO.builder()
                                .name("new-logbooks")
                                .build()
                )
        );

        assertThat(creationResult).isNotNull();
        assertThat(creationResult.getErrorCode()).isEqualTo(0);
        assertThat(creationResult.getPayload()).isNotEmpty();

        ApiResultResponse<String> newTagResult = assertDoesNotThrow(
                () -> testControllerHelperService.createNewLogbookTags(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        creationResult.getPayload(),
                        NewTagDTO
                                .builder()
                                .name("new-tag")
                                .build()
                )
        );
        assertThat(newTagResult).isNotNull();
        assertThat(newTagResult.getErrorCode()).isEqualTo(0);
        assertThat(newTagResult.getPayload()).isNotEmpty();

        ApiResultResponse<List<TagDTO>> allTagResult = assertDoesNotThrow(
                () -> testControllerHelperService.getLogbookTags(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        creationResult.getPayload()
                )
        );

        assertThat(allTagResult).isNotNull();
        assertThat(allTagResult.getErrorCode()).isEqualTo(0);
        assertThat(allTagResult.getPayload()).hasSize(1);
        assertThat(allTagResult.getPayload()).extracting("name").contains("new-tag");
        assertThat(allTagResult.getPayload().getFirst().description()).isNull();
    }

    @Test
    public void createAndGetLogbookTags() throws Exception {
        ApiResultResponse<String> creationResult = assertDoesNotThrow(
                () -> testControllerHelperService.createNewLogbook(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        NewLogbookDTO.builder()
                                .name("new-logbooks")
                                .build()
                )
        );

        assertThat(creationResult).isNotNull();
        assertThat(creationResult.getErrorCode()).isEqualTo(0);
        assertThat(creationResult.getPayload()).isNotEmpty();

        ApiResultResponse<String> newTagResult = assertDoesNotThrow(
                () -> testControllerHelperService.createNewLogbookTags(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        creationResult.getPayload(),
                        NewTagDTO
                                .builder()
                                .name("new-tag")
                                .description("new-tag-description")
                                .build()
                )
        );
        assertThat(newTagResult).isNotNull();
        assertThat(newTagResult.getErrorCode()).isEqualTo(0);
        assertThat(newTagResult.getPayload()).isNotEmpty();

        ApiResultResponse<List<TagDTO>> allTagResult = assertDoesNotThrow(
                () -> testControllerHelperService.getLogbookTags(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        creationResult.getPayload()
                )
        );

        assertThat(allTagResult).isNotNull();
        assertThat(allTagResult.getErrorCode()).isEqualTo(0);
        assertThat(allTagResult.getPayload()).hasSize(1);
        assertThat(allTagResult.getPayload()).extracting("name").contains("new-tag");
        assertThat(allTagResult.getPayload()).extracting("description").contains("new-tag-description");
    }


    @Test
    public void failCreatingTwoSameTagsOnTheSameLogbook() {
        ApiResultResponse<String> creationResult = assertDoesNotThrow(
                () -> testControllerHelperService.createNewLogbook(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        NewLogbookDTO.builder()
                                .name("new-logbooks")
                                .build()
                )
        );
        assertThat(creationResult).isNotNull();
        assertThat(creationResult.getErrorCode()).isEqualTo(0);
        assertThat(creationResult.getPayload()).isNotEmpty();

        ApiResultResponse<String> newTagResult = assertDoesNotThrow(
                () -> testControllerHelperService.createNewLogbookTags(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        creationResult.getPayload(),
                        NewTagDTO
                                .builder()
                                .name("new-tag")
                                .build()
                )
        );
        assertThat(newTagResult).isNotNull();
        assertThat(newTagResult.getErrorCode()).isEqualTo(0);
        assertThat(newTagResult.getPayload()).isNotEmpty();

        TagAlreadyExists tagAlreadyExistsException = assertThrows(
                TagAlreadyExists.class,
                () -> testControllerHelperService.createNewLogbookTags(
                        mockMvc,
                        status().isConflict(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        creationResult.getPayload(),
                        NewTagDTO
                                .builder()
                                .name("New Tag")
                                .build()
                )
        );
        assertThat(tagAlreadyExistsException.getErrorCode()).isEqualTo(-2);
    }

    @Test
    public void createAndGetShifts() throws Exception {
        ApiResultResponse<String> creationResult = assertDoesNotThrow(
                () -> testControllerHelperService.createNewLogbook(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        NewLogbookDTO.builder()
                                .name("new-logbooks")
                                .build()
                )
        );

        assertThat(creationResult).isNotNull();
        assertThat(creationResult.getErrorCode()).isEqualTo(0);
        assertThat(creationResult.getPayload()).isNotEmpty();

        ApiResultResponse<Boolean> replacementResult = assertDoesNotThrow(
                () -> testControllerHelperService.replaceShiftForLogbook(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        creationResult.getPayload(),
                        List.of(
                                ShiftDTO.builder()
                                        .name("Shift A")
                                        .from("08:00")
                                        .to("10:59")
                                        .build()
                        )
                )
        );
        assertThat(replacementResult).isNotNull();
        assertThat(replacementResult.getErrorCode()).isEqualTo(0);

        ApiResultResponse<LogbookDTO> logbookResult = assertDoesNotThrow(
                () -> testControllerHelperService.getLogbookByID(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        creationResult.getPayload()
                )
        );

        assertThat(logbookResult).isNotNull();
        assertThat(logbookResult.getErrorCode()).isEqualTo(0);
        assertThat(logbookResult.getPayload().shifts()).extracting("name").contains("Shift A");

        // replace and remove the old one

        replacementResult = assertDoesNotThrow(
                () -> testControllerHelperService.replaceShiftForLogbook(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        creationResult.getPayload(),
                        List.of(
                                ShiftDTO.builder()
                                        .name("Shift B")
                                        .from("08:00")
                                        .to("10:59")
                                        .build()
                        )
                )
        );
        assertThat(replacementResult).isNotNull();
        assertThat(replacementResult.getErrorCode()).isEqualTo(0);

        //check
        logbookResult = assertDoesNotThrow(
                () -> testControllerHelperService.getLogbookByID(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        creationResult.getPayload()
                )
        );

        assertThat(logbookResult).isNotNull();
        assertThat(logbookResult.getErrorCode()).isEqualTo(0);
        assertThat(logbookResult.getPayload().shifts()).extracting("name").contains("Shift B");

        // update Shift B and create new one
        ApiResultResponse<LogbookDTO> finalLogbookResult = logbookResult;
        replacementResult = assertDoesNotThrow(
                () -> testControllerHelperService.replaceShiftForLogbook(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        creationResult.getPayload(),
                        List.of(
                                finalLogbookResult.getPayload().shifts().get(0).toBuilder()
                                        .from("07:00")
                                        .to("07:59")
                                        .build(),
                                ShiftDTO.builder()
                                        .name("New After B")
                                        .from("08:00")
                                        .to("10:59")
                                        .build()
                        )
                )
        );
        assertThat(replacementResult).isNotNull();
        assertThat(replacementResult.getErrorCode()).isEqualTo(0);

        logbookResult = assertDoesNotThrow(
                () -> testControllerHelperService.getLogbookByID(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        creationResult.getPayload()
                )
        );

        assertThat(logbookResult).isNotNull();
        assertThat(logbookResult.getErrorCode()).isEqualTo(0);
        assertThat(logbookResult.getPayload().shifts()).extracting("from").contains("07:00", "08:00");
    }

    @Test
    public void updateLogbook() throws Exception {
        ApiResultResponse<String> creationResult = assertDoesNotThrow(
                () -> testControllerHelperService.createNewLogbook(
                        mockMvc,
                        status().isCreated(),
                        Optional.of("user1@slac.stanford.edu"),
                        NewLogbookDTO.builder()
                                .name("new-logbooks")
                                .build()
                )
        );

        assertThat(creationResult).isNotNull();
        assertThat(creationResult.getErrorCode()).isEqualTo(0);
        assertThat(creationResult.getPayload()).isNotEmpty();

        // get the full logbook
        ApiResultResponse<LogbookDTO> fullLogbook = assertDoesNotThrow(
                () -> testControllerHelperService.getLogbookByID(
                        mockMvc,
                        status().isOk(),
                        Optional.of("user1@slac.stanford.edu"),
                        creationResult.getPayload()
                )
        );
        fullLogbook = assertDoesNotThrow(
                () -> testControllerHelperService.getLogbookByID(
                        mockMvc,
                        status().isOk(),
                        Optional.of("user1@slac.stanford.edu"),
                        creationResult.getPayload()
                )
        );
        assertThat(fullLogbook).isNotNull();
        assertThat(fullLogbook.getErrorCode()).isEqualTo(0);
        assertThat(fullLogbook.getPayload().name()).isEqualTo("new-logbooks");
        assertThat(fullLogbook.getPayload().tags()).isEmpty();
        assertThat(fullLogbook.getPayload().shifts()).isEmpty();
        assertThat(fullLogbook.getPayload().readAll()).isFalse();
        assertThat(fullLogbook.getPayload().writeAll()).isFalse();



        ApiResultResponse<Boolean> replacementResult = assertDoesNotThrow(
                () -> testControllerHelperService.updateLogbook(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        creationResult.getPayload(),
                        UpdateLogbookDTO
                                .builder()
                                .name("updated name")
                                .tags(
                                        List.of(
                                                TagDTO.builder()
                                                        .name("tag-1")
                                                        .build()
                                        )
                                )
                                .shifts(
                                        List.of(
                                                ShiftDTO.builder()
                                                        .name("Shift A")
                                                        .from("08:00")
                                                        .to("10:59")
                                                        .build()
                                        )
                                )
                                .build()
                )
        );
        assertThat(replacementResult).isNotNull();
        assertThat(replacementResult.getErrorCode()).isEqualTo(0);

        ApiResultResponse<LogbookDTO> fullLogbookUpdated = assertDoesNotThrow(
                () -> testControllerHelperService.getLogbookByID(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        creationResult.getPayload()
                )
        );
        assertThat(fullLogbookUpdated.getErrorCode()).isEqualTo(0);
        assertThat(fullLogbookUpdated.getPayload().name()).isEqualTo("updated-name");
        assertThat(fullLogbookUpdated.getPayload().tags()).hasSize(1).extracting("name").contains("tag-1");
        assertThat(fullLogbookUpdated.getPayload().shifts()).hasSize(1).extracting("name").contains("Shift A");
    }

    @Test
    public void updateLogbookReadAndWriteAll() throws Exception {
        ApiResultResponse<String> creationResult = assertDoesNotThrow(
                () -> testControllerHelperService.createNewLogbook(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        NewLogbookDTO.builder()
                                .name("new-logbooks")
                                .build()
                )
        );

        assertThat(creationResult).isNotNull();
        assertThat(creationResult.getErrorCode()).isEqualTo(0);
        assertThat(creationResult.getPayload()).isNotEmpty();

        ApiResultResponse<Boolean> replacementResult = assertDoesNotThrow(
                () -> testControllerHelperService.updateLogbook(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        creationResult.getPayload(),
                        UpdateLogbookDTO
                                .builder()
                                .name("updated name")
                                .writeAll(true)
                                .readAll(true)
                                .tags(emptyList())
                                .shifts(emptyList())
                                .build()
                )
        );
        assertThat(replacementResult).isNotNull();
        assertThat(replacementResult.getErrorCode()).isEqualTo(0);

        ApiResultResponse<LogbookDTO> fullLogbook = assertDoesNotThrow(
                () -> testControllerHelperService.getLogbookByID(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        creationResult.getPayload()
                )
        );
        assertThat(fullLogbook.getErrorCode()).isEqualTo(0);
        assertThat(fullLogbook.getPayload().readAll()).isTrue();
        assertThat(fullLogbook.getPayload().writeAll()).isTrue();
    }

    @Test
    public void updateLogbookOnlyShifts() throws Exception {
        ApiResultResponse<String> creationResult = assertDoesNotThrow(
                () -> testControllerHelperService.createNewLogbook(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        NewLogbookDTO.builder()
                                .name("new-logbooks")
                                .build()
                )
        );

        assertThat(creationResult).isNotNull();
        assertThat(creationResult.getErrorCode()).isEqualTo(0);
        assertThat(creationResult.getPayload()).isNotEmpty();

        ApiResultResponse<Boolean> replacementResult = assertDoesNotThrow(
                () -> testControllerHelperService.updateLogbook(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        creationResult.getPayload(),
                        UpdateLogbookDTO
                                .builder()
                                .name("updated name")
                                .tags(
                                        emptyList()
                                )
                                .shifts(
                                        List.of(
                                                ShiftDTO.builder()
                                                        .id(null)
                                                        .name("Morning shift")
                                                        .from("16:09")
                                                        .to("17:09")
                                                        .build()
                                        )
                                )
                                .build()
                )
        );
        assertThat(replacementResult).isNotNull();
        assertThat(replacementResult.getErrorCode()).isEqualTo(0);

        ApiResultResponse<LogbookDTO> fullLogbook = assertDoesNotThrow(
                () -> testControllerHelperService.getLogbookByID(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        creationResult.getPayload()
                )
        );
        assertThat(fullLogbook.getErrorCode()).isEqualTo(0);
        assertThat(fullLogbook.getPayload().name()).isEqualTo("updated-name");
        assertThat(fullLogbook.getPayload().shifts()).hasSize(1).extracting("name").contains("Morning shift");
    }


    @Test
    public void testGetOneLogbookTags() {
        ApiResultResponse<String> newLogbookResult = assertDoesNotThrow(
                () -> testControllerHelperService.createNewLogbook(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        NewLogbookDTO
                                .builder()
                                .name("Logbook1")
                                .build()
                )
        );
        assertThat(newLogbookResult).isNotNull();
        assertThat(newLogbookResult.getErrorCode()).isEqualTo(0);

        for (int idx = 0; idx <= 99; idx++) {
            int finalIdx = idx;
            ApiResultResponse<String> newTagResult = assertDoesNotThrow(
                    () -> testControllerHelperService.createNewLogbookTags(
                            mockMvc,
                            status().isCreated(),
                            Optional.of(
                                    "user1@slac.stanford.edu"
                            ),
                            newLogbookResult.getPayload(),
                            NewTagDTO
                                    .builder()
                                    .name(String.format("Tag-%d", finalIdx))
                                    .build()
                    )
            );
            assertThat(newTagResult).isNotNull();
            assertThat(newTagResult.getErrorCode()).isEqualTo(0);
        }

        ApiResultResponse<List<TagDTO>> allTagsResult = assertDoesNotThrow(
                () -> testControllerHelperService.tagControllerGetAllTags(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        Optional.empty()
                )
        );
        assertThat(allTagsResult).isNotNull();
        assertThat(allTagsResult.getErrorCode()).isEqualTo(0);
        assertThat(allTagsResult.getPayload().size()).isEqualTo(100);
    }

    @Test
    public void testGetFromAllLogbookTags() {
        //add first logbooks
        ApiResultResponse<String> newLogbookResult = assertDoesNotThrow(
                () -> testControllerHelperService.createNewLogbook(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        NewLogbookDTO
                                .builder()
                                .name("Logbook1")
                                .build()
                )
        );
        assertThat(newLogbookResult).isNotNull();
        assertThat(newLogbookResult.getErrorCode()).isEqualTo(0);

        for (int idx = 0; idx <= 99; idx++) {
            int finalIdx = idx;
            ApiResultResponse<String> newTagResult = assertDoesNotThrow(
                    () -> testControllerHelperService.createNewLogbookTags(
                            mockMvc,
                            status().isCreated(),
                            Optional.of(
                                    "user1@slac.stanford.edu"
                            ),
                            newLogbookResult.getPayload(),
                            NewTagDTO
                                    .builder()
                                    .name(String.format("Tag-%d", finalIdx))
                                    .build()
                    )
            );
            assertThat(newTagResult).isNotNull();
            assertThat(newTagResult.getErrorCode()).isEqualTo(0);
        }

        //add second logbooks
        ApiResultResponse<String> newLogbookResultTwo = assertDoesNotThrow(
                () -> testControllerHelperService.createNewLogbook(
                        mockMvc,
                        status().isCreated(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        NewLogbookDTO
                                .builder()
                                .name("Logbook2")
                                .build()
                )
        );
        assertThat(newLogbookResultTwo).isNotNull();
        assertThat(newLogbookResultTwo.getErrorCode()).isEqualTo(0);

        for (int idx = 50; idx <= 149; idx++) {
            int finalIdx = idx;
            ApiResultResponse<String> newTagResult = assertDoesNotThrow(
                    () -> testControllerHelperService.createNewLogbookTags(
                            mockMvc,
                            status().isCreated(),
                            Optional.of(
                                    "user1@slac.stanford.edu"
                            ),
                            newLogbookResultTwo.getPayload(),
                            NewTagDTO
                                    .builder()
                                    .name(String.format("Tag-%d", finalIdx))
                                    .build()
                    )
            );
            assertThat(newTagResult).isNotNull();
            assertThat(newTagResult.getErrorCode()).isEqualTo(0);
        }

        ApiResultResponse<List<TagDTO>> allTagsResult = assertDoesNotThrow(
                () -> testControllerHelperService.tagControllerGetAllTags(
                        mockMvc,
                        status().isOk(),
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        Optional.empty()
                )
        );
        assertThat(allTagsResult).isNotNull();
        assertThat(allTagsResult.getErrorCode()).isEqualTo(0);
        assertThat(allTagsResult.getPayload().size()).isEqualTo(200);
    }
}