package edu.stanford.slac.elog_plus.service;

import edu.stanford.slac.ad.eed.baselib.api.v1.dto.NewAuthenticationTokenDTO;
import edu.stanford.slac.ad.eed.baselib.api.v1.dto.PersonDTO;
import edu.stanford.slac.ad.eed.baselib.api.v2.dto.NewLocalGroupDTO;
import edu.stanford.slac.ad.eed.baselib.config.AppProperties;
import edu.stanford.slac.ad.eed.baselib.service.AuthService;
import edu.stanford.slac.ad.eed.baselib.service.PeopleGroupService;
import edu.stanford.slac.elog_plus.api.v1.dto.NewLogbookDTO;
import edu.stanford.slac.elog_plus.service.LogbookService;
import jakarta.validation.constraints.NotNull;
import org.apache.kafka.clients.admin.AdminClient;
import org.assertj.core.api.AssertionsForClassTypes;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDate;
import java.util.*;

import static edu.stanford.slac.elog_plus.api.v1.mapper.EntryMapper.ELOG_ENTRY_REF;
import static edu.stanford.slac.elog_plus.api.v1.mapper.EntryMapper.ELOG_ENTRY_REF_ID;
import static edu.stanford.slac.elog_plus.utility.StringUtilities.logbookNameNormalization;
import static edu.stanford.slac.elog_plus.utility.StringUtilities.tokenNameNormalization;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@Validated
@Service
public class SharedUtilityService {
    @Autowired
    private AppProperties appProperties;
    @Autowired
    private LogbookService logbookService;
    @Autowired
    private AuthService authService;
    @Autowired
    private PeopleGroupService peopleGroupService;
    @Value("${edu.stanford.slac.elog-plus.image-preview-topic}")
    private String imagePreviewTopic;
    @Autowired
    private KafkaAdmin kafkaAdmin;

    /**
     * Create a default group for testing
     */
    public List<String> createDefaultGroup() {
        List<String> ids = new LinkedList<>();
        ids.add(
                assertDoesNotThrow(
                        () -> authService.createLocalGroup(
                                NewLocalGroupDTO
                                        .builder()
                                        .name("group-1")
                                        .description("group-1 description")
                                        .members(List.of("user1@slac.stanford.edu"))
                                        .build()
                        )
                )
        );
        ids.add(
                assertDoesNotThrow(
                        () -> authService.createLocalGroup(
                                NewLocalGroupDTO
                                        .builder()
                                        .name("group-2")
                                        .description("group-2 description")
                                        .members(List.of("user1@slac.stanford.edu", "user2@slac.stanford.edu"))
                                        .build()
                        )
                )
        );
        return ids;
    }

    /**
     * Create a new token and return the email of the token
     *
     * @param tokenName the name of the token
     * @return the email of the token
     */
    public String createNewToken(String tokenName, LocalDate expiration) {
        var newAuthenticationToken = assertDoesNotThrow(
                () -> authService.addNewApplicationAuthenticationToken(
                        NewAuthenticationTokenDTO
                                .builder()
                                .name(tokenName)
                                .expiration(expiration)
                                .build(),
                        false
                )
        );

        return newAuthenticationToken.email();
    }

    public String getTestLogbook() {
        return getTestLogbook("new-logbooks");
    }

    public String getTestLogbook(String logbookName) {
        String newLogbookID = assertDoesNotThrow(
                () -> logbookService.createNew(
                        NewLogbookDTO
                                .builder()
                                .name(logbookName)
                                .build()
                )
        );
        AssertionsForClassTypes.assertThat(newLogbookID).isNotNull().isNotEmpty();
        return newLogbookID;
    }

    public String getTokenEmailForApplicationToken(String tokenName) {
        return "%s@%s".formatted(
                tokenNameNormalization(tokenName),
                appProperties.getAppEmailPostfix());
    }

    public String getTokenEmailForGlobalToken(String tokenName) {
        return "%s@%s".formatted(
                tokenNameNormalization(tokenName),
                appProperties.getAuthenticationTokenDomain());
    }

    public Authentication getAuthenticationMockForFirstRootUser() {
        if (appProperties.getRootUserList().isEmpty()) {
            return null;
        } else {
            return getAuthenticationMockForEmail(
                    appProperties.getRootUserList().get(0)
            );
        }
    }


    public PersonDTO getPersonForEmail(String mail) {
        return peopleGroupService.findPerson(getAuthenticationMockForEmail(mail));
    }

    public Authentication getAuthenticationMockForEmail(String email) {
        return new Authentication() {
            @Override
            public Collection<? extends GrantedAuthority> getAuthorities() {
                return null;
            }

            @Override
            public Object getCredentials() {
                return email;
            }

            @Override
            public Object getDetails() {
                return null;
            }

            @Override
            public Object getPrincipal() {
                return email;
            }

            @Override
            public boolean isAuthenticated() {
                return true;
            }

            @Override
            public void setAuthenticated(boolean isAuthenticated) throws IllegalArgumentException {

            }

            @Override
            public String getName() {
                return email;
            }
        };
    }

    public String createReferenceHtmlFragment(String text, @NotNull List<String> referencedEntryId) {
        Element fragmentRef1 = new Element("div");
        if(text!=null)fragmentRef1.appendText("This is a text with reference");
        for (String id : referencedEntryId) {
            fragmentRef1.appendElement(ELOG_ENTRY_REF).attr(ELOG_ENTRY_REF_ID, id);
        }
        return fragmentRef1.html();
    }

    public boolean htmlContainsReferenceWithId(String text, String newSupersedeEntryId) {
        boolean found = false;
        Document document = Jsoup.parseBodyFragment(text);
        Elements elements = document.select(ELOG_ENTRY_REF);
        for (Element element : elements) {
            // Get the 'id' attribute
            if(!element.hasAttr(ELOG_ENTRY_REF_ID)) continue;
            String id = element.attr(ELOG_ENTRY_REF_ID);
            if(id.isEmpty() || id.compareToIgnoreCase(newSupersedeEntryId)!=0) continue;
            found = true;
            break;
        }
        return found;
    }

    public void cleanKafkaPreviewTopic() {
        try (AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            Set<String> existingTopics = adminClient.listTopics().names().get();
            List<String> topicsToDelete = List.of(
                    imagePreviewTopic,
                    String.format("%s-retry-2000", imagePreviewTopic),
                    String.format("%s-retry-4000", imagePreviewTopic)
            );

            // Delete topics that actually exist
            topicsToDelete.stream()
                    .filter(existingTopics::contains)
                    .forEach(topic -> {
                        try {
                            adminClient.deleteTopics(Collections.singletonList(topic)).all().get();
                        } catch (Exception e) {
                            System.err.println("Failed to delete topic " + topic + ": " + e.getMessage());
                        }
                    });
        } catch (Exception e) {
            throw new RuntimeException("Failed to recreate Kafka topic", e);
        }
    }
}
