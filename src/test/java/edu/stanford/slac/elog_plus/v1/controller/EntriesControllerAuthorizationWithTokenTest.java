package edu.stanford.slac.elog_plus.v1.controller;

import edu.stanford.slac.ad.eed.baselib.api.v1.dto.*;
import edu.stanford.slac.ad.eed.baselib.config.AppProperties;
import edu.stanford.slac.ad.eed.baselib.model.AuthenticationToken;
import edu.stanford.slac.ad.eed.baselib.model.Authorization;
import edu.stanford.slac.ad.eed.baselib.model.LocalGroup;
import edu.stanford.slac.ad.eed.baselib.service.AuthService;
import edu.stanford.slac.elog_plus.api.v1.dto.*;
import edu.stanford.slac.elog_plus.api.v1.dto.NewAuthorizationDTO;
import edu.stanford.slac.elog_plus.model.*;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static edu.stanford.slac.ad.eed.baselib.api.v1.dto.AuthorizationOwnerTypeDTO.Token;
import static edu.stanford.slac.ad.eed.baselib.api.v1.dto.AuthorizationOwnerTypeDTO.User;
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
public class EntriesControllerAuthorizationWithTokenTest {
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

    private List<String> groupIds;

    @BeforeEach
    public void preTest() {
        mongoTemplate.remove(new Query(), Entry.class);
        mongoTemplate.remove(new Query(), Attachment.class);
        mongoTemplate.remove(new Query(), Logbook.class);

        //reset authorizations
        mongoTemplate.remove(new Query(), Authorization.class);
        mongoTemplate.remove(new Query(), AuthenticationToken.class);
        mongoTemplate.remove(new Query(), LocalGroup.class);
        appProperties.getRootUserList().clear();
        appProperties.getRootUserList().add("user1@slac.stanford.edu");
        authService.updateRootUser();
        groupIds = sharedUtilityService.createDefaultGroup();
    }

    @Test
    public void createNewLogSuccessWithAuthTokenOnLogbook() throws Exception {
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
                                        .ownerType(User)
                                        .ownerId("user2@slac.stanford.edu")
                                        .permission(AuthorizationTypeDTO.Write)
                                        .build(),
                                NewAuthorizationDTO
                                        .builder()
                                        .ownerType(User)
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
                                                Set.of(
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
                                                Set.of(
                                                        newLogBookResult.getPayload()
                                                )
                                        )
                                        .text("This is a log for test")
                                        .title("A very wonderful log")
                                        .build()
                        )
                );

        assertThat(newLogIdForAdmin.getErrorCode()).isEqualTo(0);
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
                                                Set.of(
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
                                        .ownerType(User)
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
                                        .ownerType(User)
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
                                                Set.of(
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
    public void createNewLogSuccessWithAuthenticationTokenOnLogbook() throws Exception {
        var newApplicationsId = assertDoesNotThrow(
                () -> testControllerHelperService.applicationControllerCreateNewApplication(
                        mockMvc,
                        status().isCreated(),
                        Optional.of("user1@slac.stanford.edu"),
                        List.of(
                                NewApplicationDTO
                                        .builder()
                                        .name("token-a")
                                        .expiration(LocalDate.of(2023,12,31))
                                        .build(),
                                NewApplicationDTO
                                        .builder()
                                        .name("token-b")
                                        .expiration(LocalDate.of(2023,12,31))
                                        .build()
                        )
                )
        );

        var newApp1result = assertDoesNotThrow(
                ()-> testControllerHelperService.applicationControllerFindApplicationById(
                        mockMvc,
                        status().isOk(),
                        Optional.of("user1@slac.stanford.edu"),
                        newApplicationsId.get(0),
                        Optional.empty()
                )
        );
        var newApp2result = assertDoesNotThrow(
                ()-> testControllerHelperService.applicationControllerFindApplicationById(
                        mockMvc,
                        status().isOk(),
                        Optional.of("user1@slac.stanford.edu"),
                        newApplicationsId.get(1),
                        Optional.empty()
                )
        );
        var newLogBookResult = assertDoesNotThrow(
                () -> testControllerHelperService.getNewLogbookWithNameWithAuthorizationAndAppToken(
                        mockMvc,
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        "LogbookAuthTest1",
                        List.of(
                                NewAuthorizationDTO
                                        .builder()
                                        .ownerType(Token)
                                        .ownerId(
                                                newApp1result.getPayload().id()
                                        )
                                        .permission(AuthorizationTypeDTO.Write)
                                        .build(),
                                NewAuthorizationDTO
                                        .builder()
                                        .ownerType(Token)
                                        .ownerId(
                                                newApp2result.getPayload().id()
                                        )
                                        .permission(AuthorizationTypeDTO.Admin)
                                        .build()
                        )
                )
        );
        assertThat(newLogBookResult.getErrorCode()).isEqualTo(0);

        // try to create with writer token
        var newLogIdForWriter =
                assertDoesNotThrow(
                        () -> testControllerHelperService.createNewLog(
                                mockMvc,
                                status().isCreated(),
                                Optional.of(
                                        newApp1result.getPayload().email()
                                ),
                                EntryNewDTO
                                        .builder()
                                        .logbooks(
                                                Set.of(
                                                        newLogBookResult.getPayload()
                                                )
                                        )
                                        .text("This is a log for test")
                                        .title("A very wonderful log")
                                        .build()
                        )
                );

        assertThat(newLogIdForWriter.getErrorCode()).isEqualTo(0);

        // try to write with the admin
        var newLogIdForAdmin =
                assertDoesNotThrow(
                        () -> testControllerHelperService.createNewLog(
                                mockMvc,
                                status().isCreated(),
                                Optional.of(
                                        newApp2result.getPayload().email()
                                ),
                                EntryNewDTO
                                        .builder()
                                        .logbooks(
                                                Set.of(
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
    public void createNewLogSuccessWithGlobalAuthentication() throws Exception {
        var tokensEmail = testControllerHelperService.applicationControllerCreateNewApplication(
                mockMvc,
                status().isCreated(),
                Optional.of("user1@slac.stanford.edu"),
                List.of(
                        NewApplicationDTO
                                .builder()
                                .name("token-a")
                                .expiration(LocalDate.of(2023,12,31))
                                .build(),
                        NewApplicationDTO
                                .builder()
                                .name("token-b")
                                .expiration(LocalDate.of(2023,12,31))
                                .build()

                )
        );

        var app1Result = testControllerHelperService.applicationControllerFindApplicationById(
                mockMvc,
                status().isOk(),
                Optional.of("user1@slac.stanford.edu"),
                tokensEmail.get(0),
                Optional.empty()
                );
        assertThat(app1Result.getErrorCode()).isEqualTo(0);

        var app2Result = testControllerHelperService.applicationControllerFindApplicationById(
                mockMvc,
                status().isOk(),
                Optional.of("user1@slac.stanford.edu"),
                tokensEmail.get(1),
                Optional.empty()
        );
        assertThat(app2Result.getErrorCode()).isEqualTo(0);

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
                                        .ownerType(Token)
                                        .ownerId(app1Result.getPayload().id())
                                        .permission(AuthorizationTypeDTO.Write)
                                        .build(),
                                NewAuthorizationDTO
                                        .builder()
                                        .ownerType(Token)
                                        .ownerId(app2Result.getPayload().id())
                                        .permission(AuthorizationTypeDTO.Admin)
                                        .build()
                        )
                )
        );
        assertThat(newLogBookResult.getErrorCode()).isEqualTo(0);

        // try to create with writer token
        var newLogIdForWriter =
                assertDoesNotThrow(
                        () -> testControllerHelperService.createNewLog(
                                mockMvc,
                                status().isCreated(),
                                Optional.of(
                                        app1Result.getPayload().email()
                                ),
                                EntryNewDTO
                                        .builder()
                                        .logbooks(
                                                Set.of(
                                                        newLogBookResult.getPayload()
                                                )
                                        )
                                        .text("This is a log for test")
                                        .title("A very wonderful log")
                                        .build()
                        )
                );

        assertThat(newLogIdForWriter.getErrorCode()).isEqualTo(0);

        // try to write with the admin
        var newLogIdForAdmin =
                assertDoesNotThrow(
                        () -> testControllerHelperService.createNewLog(
                                mockMvc,
                                status().isCreated(),
                                Optional.of(
                                        app2Result.getPayload().email()
                                ),
                                EntryNewDTO
                                        .builder()
                                        .logbooks(
                                                Set.of(
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
}
