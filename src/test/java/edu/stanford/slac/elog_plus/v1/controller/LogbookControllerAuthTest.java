package edu.stanford.slac.elog_plus.v1.controller;

import edu.stanford.slac.ad.eed.baselib.api.v1.dto.ApiResultResponse;
import edu.stanford.slac.ad.eed.baselib.api.v1.dto.AuthorizationOwnerTypeDTO;
import edu.stanford.slac.ad.eed.baselib.api.v1.dto.AuthorizationTypeDTO;
import edu.stanford.slac.ad.eed.baselib.api.v2.dto.NewLocalGroupDTO;
import edu.stanford.slac.ad.eed.baselib.config.AppProperties;
import edu.stanford.slac.ad.eed.baselib.model.AuthenticationToken;
import edu.stanford.slac.ad.eed.baselib.model.Authorization;
import edu.stanford.slac.ad.eed.baselib.model.LocalGroup;
import edu.stanford.slac.ad.eed.baselib.service.AuthService;
import edu.stanford.slac.elog_plus.api.v1.dto.*;
import edu.stanford.slac.elog_plus.model.Entry;
import edu.stanford.slac.elog_plus.model.Logbook;
import org.apache.kafka.clients.admin.AdminClient;
import org.assertj.core.api.Condition;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static edu.stanford.slac.ad.eed.baselib.api.v1.dto.AuthorizationTypeDTO.*;
import static java.util.Collections.emptyList;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@SpringBootTest()
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(MockitoExtension.class)
@ActiveProfiles({"test"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class LogbookControllerAuthTest {
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
    private AdminClient adminClient;
    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    public void preTest() {
        adminClient.deleteTopics(List.of("elog-plus-import-entry"));
        mongoTemplate.remove(new Query(), Logbook.class);
        mongoTemplate.remove(new Query(), Entry.class);
        //reset authorizations
        mongoTemplate.remove(new Query(), Authorization.class);
        mongoTemplate.remove(new Query(), LocalGroup.class);
        mongoTemplate.remove(new Query(), AuthenticationToken.class);
        appProperties.getRootUserList().clear();
        appProperties.getRootUserList().add("user1@slac.stanford.edu");
        authService.updateRootUser();

        clearCache();
    }

    private void clearCache() {
        cacheManager.getCacheNames().forEach(cacheName -> Objects.requireNonNull(cacheManager.getCache(cacheName)).clear());
    }

    @Test
    public void testGetAllLogbookForAuthType() {
        var newLogbookApiResultOne = testControllerHelperService.getNewLogbookWithNameWithAuthorization(
                mockMvc,
                Optional.of(
                        "user1@slac.stanford.edu"
                ),
                "new logbook",
                List.of(
                        NewAuthorizationDTO
                                .builder()
                                .ownerId("user2@slac.stanford.edu")
                                .ownerType(AuthorizationOwnerTypeDTO.User)
                                .permission(
                                        Write
                                )
                                .build(),
                        NewAuthorizationDTO
                                .builder()
                                .ownerId("user3@slac.stanford.edu")
                                .ownerType(AuthorizationOwnerTypeDTO.User)
                                .permission(
                                        Read
                                )
                                .build()
                )
        );
        var newLogbookApiResultTwo = testControllerHelperService.getNewLogbookWithNameWithAuthorization(
                mockMvc,
                Optional.of(
                        "user1@slac.stanford.edu"
                ),
                "new logbook 2",
                List.of(
                        NewAuthorizationDTO
                                .builder()
                                .ownerType(AuthorizationOwnerTypeDTO.User)
                                .ownerId("user2@slac.stanford.edu")
                                .permission(
                                        Write
                                )
                                .build()
                )
        );
        var newLogbookApiResultThree = testControllerHelperService.getNewLogbookWithNameWithAuthorization(
                mockMvc,
                Optional.of(
                        "user1@slac.stanford.edu"
                ),
                "new logbook 3",
                List.of()
        );

        var allLogbookResultUser3 = assertDoesNotThrow(
                () -> testControllerHelperService.getAllLogbook(
                        mockMvc,
                        status().isOk(),
                        Optional.of("user3@slac.stanford.edu"),
                        Optional.of(false),
                        Optional.empty()
                )
        );

        assertThat(allLogbookResultUser3)
                .isNotNull()
                .extracting(
                        ApiResultResponse::getErrorCode
                )
                .isEqualTo(0);
        assertThat(allLogbookResultUser3.getPayload())
                .hasSize(1)
                .extracting(LogbookDTO::id)
                .contains(
                        newLogbookApiResultOne.getPayload()
                );

        var allLogbookResultUser2 = assertDoesNotThrow(
                () -> testControllerHelperService.getAllLogbook(
                        mockMvc,
                        status().isOk(),
                        Optional.of("user2@slac.stanford.edu"),
                        Optional.of(false),
                        Optional.empty()
                )
        );

        assertThat(allLogbookResultUser2)
                .isNotNull()
                .extracting(
                        ApiResultResponse::getErrorCode
                )
                .isEqualTo(0);
        assertThat(allLogbookResultUser2.getPayload())
                .hasSize(2)
                .extracting(LogbookDTO::id)
                .contains(
                        newLogbookApiResultOne.getPayload(),
                        newLogbookApiResultTwo.getPayload()
                );

        var allLogbookResultUser2Readable = assertDoesNotThrow(
                () -> testControllerHelperService.getAllLogbook(
                        mockMvc,
                        status().isOk(),
                        Optional.of("user2@slac.stanford.edu"),
                        Optional.of(false),
                        Optional.of(
                                Read.name()
                        )
                )
        );

        assertThat(allLogbookResultUser2Readable)
                .isNotNull()
                .extracting(
                        ApiResultResponse::getErrorCode
                )
                .isEqualTo(0);
        assertThat(allLogbookResultUser2Readable.getPayload())
                .hasSize(2)
                .extracting(LogbookDTO::id)
                .contains(
                        newLogbookApiResultOne.getPayload()
                );

        var allLogbookResultUser1 = assertDoesNotThrow(
                () -> testControllerHelperService.getAllLogbook(
                        mockMvc,
                        status().isOk(),
                        Optional.of("user1@slac.stanford.edu"),
                        Optional.of(false),
                        Optional.empty()
                )
        );

        assertThat(allLogbookResultUser1)
                .isNotNull()
                .extracting(
                        ApiResultResponse::getErrorCode
                )
                .isEqualTo(0);
        assertThat(allLogbookResultUser1.getPayload())
                .hasSize(3)
                .extracting(LogbookDTO::id)
                .contains(
                        newLogbookApiResultOne.getPayload(),
                        newLogbookApiResultTwo.getPayload(),
                        newLogbookApiResultThree.getPayload()
                );
    }

    @Test
    public void testGetAllLogbookForAuthTypeUsingApplicationToken() {
        var tokensEmail = assertDoesNotThrow(
                () -> testControllerHelperService.applicationControllerCreateNewApplication(
                        mockMvc,
                        status().isCreated(),
                        Optional.of("user1@slac.stanford.edu"),
                        NewApplicationDTO
                                .builder()
                                .name("token-a")
                                .expiration(LocalDate.of(2023, 12, 31))
                                .build()

                )
        );
        var app1Result = assertDoesNotThrow(
                () -> testControllerHelperService.applicationControllerFindApplicationById(
                        mockMvc,
                        status().isOk(),
                        Optional.of("user1@slac.stanford.edu"),
                        tokensEmail.getPayload(),
                        Optional.empty()
                )
        );
        var newLogbookApiResultOne = testControllerHelperService.getNewLogbookWithNameWithAuthorizationAndAppToken(
                mockMvc,
                Optional.of(
                        "user1@slac.stanford.edu"
                ),
                "new logbook",
                List.of(
                        NewAuthorizationDTO
                                .builder()
                                .ownerId("user2@slac.stanford.edu")
                                .ownerType(AuthorizationOwnerTypeDTO.User)
                                .permission(Write)
                                .build(),
                        NewAuthorizationDTO
                                .builder()
                                .ownerId(app1Result.getPayload().id())
                                .ownerType(AuthorizationOwnerTypeDTO.Token)
                                .permission(Read)
                                .build()
                )
        );
        var newLogbookApiResultTwo = testControllerHelperService.getNewLogbookWithNameWithAuthorization(
                mockMvc,
                Optional.of(
                        "user1@slac.stanford.edu"
                ),
                "new logbook 2",
                List.of(
                        NewAuthorizationDTO
                                .builder()
                                .ownerType(AuthorizationOwnerTypeDTO.User)
                                .ownerId("user2@slac.stanford.edu")
                                .permission(Write)
                                .build()
                )
        );

        var newLogbookApiResultThree = testControllerHelperService.getNewLogbookWithNameWithAuthorization(
                mockMvc,
                Optional.of(
                        "user1@slac.stanford.edu"
                ),
                "new logbook 3",
                List.of()
        );

        var allLogbookResultUser3 = assertDoesNotThrow(
                () -> testControllerHelperService.getAllLogbook(
                        mockMvc,
                        status().isOk(),
                        Optional.of("user3@slac.stanford.edu"),
                        Optional.of(false),
                        Optional.empty()
                )
        );

        assertThat(allLogbookResultUser3)
                .isNotNull()
                .extracting(
                        ApiResultResponse::getErrorCode
                )
                .isEqualTo(0);
        assertThat(allLogbookResultUser3.getPayload())
                .hasSize(0);
    }

    @Test
    public void getAsReadAuthorizedUser() {
        var newLogbookApiResultOneReader = assertDoesNotThrow(
                () -> testControllerHelperService.getNewLogbookWithNameWithAuthorizationAndAppToken(
                        mockMvc,
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        "new logbook",
                        List.of(
                                NewAuthorizationDTO
                                        .builder()
                                        .ownerId("user2@slac.stanford.edu")
                                        .ownerType(AuthorizationOwnerTypeDTO.User)
                                        .permission(
                                                Read
                                        )
                                        .build()
                        )
                )
        );
        var newLogbookApiResultTwoWriter = assertDoesNotThrow(
                () -> testControllerHelperService.getNewLogbookWithNameWithAuthorizationAndAppToken(
                        mockMvc,
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        "new logbook 2",
                        List.of(
                                NewAuthorizationDTO
                                        .builder()
                                        .ownerId("user2@slac.stanford.edu")
                                        .ownerType(AuthorizationOwnerTypeDTO.User)
                                        .permission(
                                                Write
                                        )
                                        .build()
                        )
                )
        );
        var newLogbookApiResultThreeReader = assertDoesNotThrow(
                () -> testControllerHelperService.getNewLogbookWithNameWithAuthorizationAndAppToken(
                        mockMvc,
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        "new logbook 3",
                        List.of(
                                NewAuthorizationDTO
                                        .builder()
                                        .ownerId("user2@slac.stanford.edu")
                                        .ownerType(AuthorizationOwnerTypeDTO.User)
                                        .permission(
                                                Read
                                        )
                                        .build()
                        )
                )
        );

        var logbookThaUser2CanRead = assertDoesNotThrow(
                () -> testControllerHelperService.getAllLogbook(
                        mockMvc,
                        status().isOk(),
                        Optional.of("user2@slac.stanford.edu"),
                        Optional.of(true),
                        Optional.of(Read.name())
                )
        );
        assertThat(logbookThaUser2CanRead.getPayload())
                .hasSize(3)
                .extracting(LogbookDTO::id)
                .contains(
                        newLogbookApiResultOneReader.getPayload(),
                        newLogbookApiResultTwoWriter.getPayload(),
                        newLogbookApiResultThreeReader.getPayload()
                );

        var logbookThaUser2CanWrite = assertDoesNotThrow(
                () -> testControllerHelperService.getAllLogbook(
                        mockMvc,
                        status().isOk(),
                        Optional.of("user2@slac.stanford.edu"),
                        Optional.of(false),
                        Optional.of(Write.name())
                )
        );
        assertThat(logbookThaUser2CanWrite.getPayload())
                .hasSize(1)
                .extracting(LogbookDTO::id)
                .contains(
                        newLogbookApiResultTwoWriter.getPayload()
                );
    }

    @Test
    public void testNewAuthorizationLogbookApiToSetUserAuth() {
        var newLogbookResult = assertDoesNotThrow(
                () -> testControllerHelperService.getNewLogbookWithNameWithAuthorizationAndAppToken(
                        mockMvc,
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        "new logbook",
                        emptyList()
                )
        );

        assertThat(newLogbookResult.getPayload()).isNotEmpty();

        var logbook = assertDoesNotThrow(
                () -> testControllerHelperService.getLogbookByID(
                        mockMvc,
                        status().isOk(),
                        Optional.of("user1@slac.stanford.edu"),
                        newLogbookResult.getPayload(),
                        Optional.of(true)
                )
        );

        assertThat(logbook.getPayload())
                .isNotNull();
        assertThat(logbook.getPayload().authorizations())
                .isNullOrEmpty();

        // apply authorization for group
        var createNewAuth = assertDoesNotThrow(
                () -> testControllerHelperService.authorizationControllerCreateNewAuthorization(
                        mockMvc,
                        status().isCreated(),
                        Optional.of("user1@slac.stanford.edu"),
                        NewAuthorizationDTO
                                .builder()
                                .resourceId(newLogbookResult.getPayload())
                                .resourceType(ResourceTypeDTO.Logbook)
                                .ownerId("user1@slac.stanford.edu")
                                .ownerType(AuthorizationOwnerTypeDTO.User)
                                .permission(Write)
                                .build()
                )
        );
        assertThat(createNewAuth.getPayload()).isTrue();
        // create group
        var groupIdResult = assertDoesNotThrow(
                () -> testControllerHelperService.groupControllerCreateNewGroup(
                        mockMvc,
                        status().isCreated(),
                        Optional.of("user1@slac.stanford.edu"),
                        NewLocalGroupDTO.builder()
                                .name("local-group-1")
                                .description("local-group-1 description")
                                .members(List.of("user1@slac.stanford.edu"))
                                .build()
                )
        );
        assertThat(groupIdResult.getPayload()).isNotEmpty();
        // apply authorization for group
        assertDoesNotThrow(
                () -> testControllerHelperService.authorizationControllerCreateNewAuthorization(
                        mockMvc,
                        status().isCreated(),
                        Optional.of("user1@slac.stanford.edu"),
                        NewAuthorizationDTO
                                .builder()
                                .resourceId(newLogbookResult.getPayload())
                                .resourceType(ResourceTypeDTO.Logbook)
                                .ownerId(groupIdResult.getPayload())
                                .ownerType(AuthorizationOwnerTypeDTO.Group)
                                .permission(Read)
                                .build()
                )
        );

        logbook = assertDoesNotThrow(
                () -> testControllerHelperService.getLogbookByID(
                        mockMvc,
                        status().isOk(),
                        Optional.of("user1@slac.stanford.edu"),
                        newLogbookResult.getPayload(),
                        Optional.of(true)
                )
        );

        assertThat(logbook.getPayload())
                .isNotNull();
        assertThat(logbook.getPayload().authorizations())
                .hasSize(2)
                .extracting(DetailsAuthorizationDTO::ownerId)
                .contains("user1@slac.stanford.edu", groupIdResult.getPayload());

        // remove authorization for all user
        clearCache();
        authService.deleteAuthorizationForResourcePrefix("/logbook/%s".formatted(newLogbookResult.getPayload()), AuthorizationOwnerTypeDTO.User);

        logbook = assertDoesNotThrow(
                () -> testControllerHelperService.getLogbookByID(
                        mockMvc,
                        status().isOk(),
                        Optional.of("user1@slac.stanford.edu"),
                        newLogbookResult.getPayload(),
                        Optional.of(true)
                )
        );
        assertThat(logbook.getPayload())
                .isNotNull();
        assertThat(logbook.getPayload().authorizations())
                .hasSize(1);

        // remove authorization for group
        clearCache();
        authService.deleteAuthorizationForResourcePrefix("/logbook/%s".formatted(newLogbookResult.getPayload()), AuthorizationOwnerTypeDTO.Group);
        // check
        logbook = assertDoesNotThrow(
                () -> testControllerHelperService.getLogbookByID(
                        mockMvc,
                        status().isOk(),
                        Optional.of("user1@slac.stanford.edu"),
                        newLogbookResult.getPayload(),
                        Optional.of(true)
                )
        );
        assertThat(logbook.getPayload())
                .isNotNull();
        assertThat(logbook.getPayload().authorizations())
                .isNullOrEmpty();
    }

    @Test
    public void testMultipleAuthorizationForUserAndGroup() {
        var newLogbookResult = assertDoesNotThrow(
                () -> testControllerHelperService.getNewLogbookWithNameWithAuthorizationAndAppToken(
                        mockMvc,
                        Optional.of(
                                "user1@slac.stanford.edu"
                        ),
                        "new logbook",
                        emptyList()
                )
        );

        assertThat(newLogbookResult.getPayload()).isNotEmpty();

        var logbook = assertDoesNotThrow(
                () -> testControllerHelperService.getLogbookByID(
                        mockMvc,
                        status().isOk(),
                        Optional.of("user1@slac.stanford.edu"),
                        newLogbookResult.getPayload(),
                        Optional.of(true)
                )
        );

        assertThat(logbook.getPayload())
                .isNotNull();
        assertThat(logbook.getPayload().authorizations())
                .isNotNull()
                .isEmpty();

        // apply authorization for user
        var createNewAuth = assertDoesNotThrow(
                () -> testControllerHelperService.authorizationControllerCreateNewAuthorization(
                        mockMvc,
                        status().isCreated(),
                        Optional.of("user1@slac.stanford.edu"),
                        NewAuthorizationDTO
                                .builder()
                                .resourceId(newLogbookResult.getPayload())
                                .resourceType(ResourceTypeDTO.Logbook)
                                .ownerId("user1@slac.stanford.edu")
                                .ownerType(AuthorizationOwnerTypeDTO.User)
                                .permission(Write)
                                .build()
                )
        );
        assertThat(createNewAuth.getPayload()).isTrue();
        createNewAuth = assertDoesNotThrow(
                () -> testControllerHelperService.authorizationControllerCreateNewAuthorization(
                        mockMvc,
                        status().isCreated(),
                        Optional.of("user1@slac.stanford.edu"),
                        NewAuthorizationDTO
                                .builder()
                                .resourceId(newLogbookResult.getPayload())
                                .resourceType(ResourceTypeDTO.Logbook)
                                .ownerId("user2@slac.stanford.edu")
                                .ownerType(AuthorizationOwnerTypeDTO.User)
                                .permission(Read)
                                .build()
                )
        );
        assertThat(createNewAuth.getPayload()).isTrue();


        // apply authorization for group
        // create group
        var group1IdResult = assertDoesNotThrow(
                () -> testControllerHelperService.groupControllerCreateNewGroup(
                        mockMvc,
                        status().isCreated(),
                        Optional.of("user1@slac.stanford.edu"),
                        NewLocalGroupDTO.builder()
                                .name("local-group-1")
                                .description("local-group-1 description")
                                .members(List.of("user1@slac.stanford.edu"))
                                .build()
                )
        );
        assertThat(group1IdResult.getPayload()).isNotEmpty();
        var group2IdResult = assertDoesNotThrow(
                () -> testControllerHelperService.groupControllerCreateNewGroup(
                        mockMvc,
                        status().isCreated(),
                        Optional.of("user1@slac.stanford.edu"),
                        NewLocalGroupDTO.builder()
                                .name("local-group-2")
                                .description("local-group-2 description")
                                .members(List.of("user1@slac.stanford.edu", "user2@slac.stanford.edu"))
                                .build()
                )
        );
        assertThat(group2IdResult.getPayload()).isNotEmpty();

        // apply authorization for group
        var newAuthResult = assertDoesNotThrow(
                () -> testControllerHelperService.authorizationControllerCreateNewAuthorization(
                        mockMvc,
                        status().isCreated(),
                        Optional.of("user1@slac.stanford.edu"),
                        NewAuthorizationDTO
                                .builder()
                                .resourceId(newLogbookResult.getPayload())
                                .resourceType(ResourceTypeDTO.Logbook)
                                .ownerId(group1IdResult.getPayload())
                                .ownerType(AuthorizationOwnerTypeDTO.Group)
                                .permission(Read)
                                .build()
                )
        );
        assertThat(newAuthResult).isNotNull();

        newAuthResult = assertDoesNotThrow(
                () -> testControllerHelperService.authorizationControllerCreateNewAuthorization(
                        mockMvc,
                        status().isCreated(),
                        Optional.of("user1@slac.stanford.edu"),
                        NewAuthorizationDTO
                                .builder()
                                .resourceId(newLogbookResult.getPayload())
                                .resourceType(ResourceTypeDTO.Logbook)
                                .ownerId(group2IdResult.getPayload())
                                .ownerType(AuthorizationOwnerTypeDTO.Group)
                                .permission(Write)
                                .build()
                )
        );
        assertThat(newAuthResult).isNotNull();

        logbook = assertDoesNotThrow(
                () -> testControllerHelperService.getLogbookByID(
                        mockMvc,
                        status().isOk(),
                        Optional.of("user1@slac.stanford.edu"),
                        newLogbookResult.getPayload(),
                        Optional.of(true)
                )
        );

        assertThat(logbook.getPayload())
                .isNotNull();
        assertThat(logbook.getPayload().authorizations())
                .hasSize(4)
                .extracting(DetailsAuthorizationDTO::ownerId)
                .contains("user1@slac.stanford.edu", "user2@slac.stanford.edu", group1IdResult.getPayload(), group2IdResult.getPayload());
        assertThat(logbook.getPayload().authorizations())
                .hasSize(4)
                .extracting(DetailsAuthorizationDTO::ownerName)
                .contains("Name1 Surname1", "Name2 Name 2.1 Surname2", "local-group-1", "local-group-2");

        var user1Auth = logbook.getPayload().authorizations().stream().filter(a -> a.ownerId().compareTo("user1@slac.stanford.edu") == 0).toList();
        assertThat(user1Auth).hasSize(1).extracting(DetailsAuthorizationDTO::permission).contains(Write);
        var user2Auth = logbook.getPayload().authorizations().stream().filter(a -> a.ownerId().compareTo("user2@slac.stanford.edu") == 0).toList();
        assertThat(user2Auth).hasSize(1).extracting(DetailsAuthorizationDTO::permission).contains(Read);
        var group1Auth = logbook.getPayload().authorizations().stream().filter(a -> a.ownerId().compareTo(group1IdResult.getPayload()) == 0).toList();
        assertThat(group1Auth).hasSize(1).extracting(DetailsAuthorizationDTO::permission).contains(Read);
        var group2Auth = logbook.getPayload().authorizations().stream().filter(a -> a.ownerId().compareTo(group2IdResult.getPayload()) == 0).toList();
        assertThat(group2Auth).hasSize(1).extracting(DetailsAuthorizationDTO::permission).contains(Write);


        // fetch only user authorization
        var user1Details = assertDoesNotThrow(
                () -> testControllerHelperService.userControllerFindUserById(
                        mockMvc,
                        status().isOk(),
                        Optional.of("user1@slac.stanford.edu"),
                        "user1@slac.stanford.edu",
                        Optional.of(true),
                        Optional.of(false)
                )
        );
        assertThat(user1Details.getPayload().authorizations())
                .hasSize(2)
                .extracting(DetailsAuthorizationDTO::permission)
                .contains(Write, Admin);
    }

    /**
     * Test to ensure that the authorization is not duplicated when the same authorization is added multiple times
     */
    static private class DetailsAuthorizationDTOIs extends Condition<DetailsAuthorizationDTO> {
        private final String resourceId;
        private final AuthorizationTypeDTO authorizationTypeDTO;

        private DetailsAuthorizationDTOIs(String resourceId, AuthorizationTypeDTO authorizationTypeDTO) {
            this.resourceId = resourceId;
            this.authorizationTypeDTO = authorizationTypeDTO;
        }

        public static DetailsAuthorizationDTOIs of(String resourceId, AuthorizationTypeDTO authorizationTypeDTO) {
            return new DetailsAuthorizationDTOIs(resourceId, authorizationTypeDTO);
        }

        @Override
        public boolean matches(DetailsAuthorizationDTO authorizationDTO) {
            return authorizationDTO != null
                    && authorizationDTO.resourceId().equals(resourceId)
                    && authorizationDTO.permission().equals(authorizationTypeDTO);
        }
    }
}
