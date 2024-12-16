package edu.stanford.slac.elog_plus.service.authorization;

import edu.stanford.slac.ad.eed.baselib.api.v1.dto.ApiResultResponse;
import edu.stanford.slac.ad.eed.baselib.exception.NotAuthorized;
import edu.stanford.slac.ad.eed.baselib.service.AuthService;
import edu.stanford.slac.elog_plus.api.v1.dto.*;
import edu.stanford.slac.elog_plus.api.v2.dto.NewEntryDTO;
import edu.stanford.slac.elog_plus.exception.LogbookNotAuthorized;
import edu.stanford.slac.elog_plus.exception.ResourceNotFound;
import edu.stanford.slac.elog_plus.service.EntryService;
import edu.stanford.slac.elog_plus.service.LogbookService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static edu.stanford.slac.ad.eed.baselib.api.v1.dto.AuthorizationTypeDTO.Read;
import static edu.stanford.slac.ad.eed.baselib.api.v1.dto.AuthorizationTypeDTO.Write;
import static edu.stanford.slac.ad.eed.baselib.exception.Utility.*;


@Service
@Validated
@AllArgsConstructor
public class EntryAuthorizationService {
    private final AuthService authService;
    private final EntryService entryService;
    private final LogbookService logbookService;

    /**
     * Check if the user can create a new entry
     *
     * @param authentication the authentication object
     * @param newEntry       the new entry to create
     * @return true if the user can create the new entry
     */
    public boolean canCreateNewEntry(Authentication authentication, @Valid EntryNewDTO newEntry) {
        List<String> allPublicWritableLogbookIds = logbookService.getAllIdsWriteAll();
        // check authenticated
        assertion(
                NotAuthorized
                        .notAuthorizedBuilder()
                        .errorCode(-1)
                        .errorDomain("EntryAuthorizationService::canCreateNewEntry")
                        .build(),
                // can write to all logbook
                () -> all
                        (
                                // this creates a list of supplier that return true if the user is authorized on logbook or if the logbook is public writable
                                newEntry.logbooks().stream()
                                        .map
                                                (
                                                        // return true if the user is authorized on logbook or if the logbook is public writable
                                                        logbookId -> (Supplier<Boolean>) () ->
                                                                any(
                                                                        () -> authService.checkAuthorizationForOwnerAuthTypeAndResourcePrefix(
                                                                                authentication,
                                                                                Write,
                                                                                "/logbook/%s".formatted(logbookId)
                                                                        ),
                                                                        () -> allPublicWritableLogbookIds.contains(logbookId)
                                                                )
                                                )
                                        .toList()
                        )
        );
        return true;
    }

    /**
     * Check if the user can create new entry
     *
     * @param authentication the authentication object
     * @param newEntry       the entry to create
     * @return true if the user can create the entry
     */
    public boolean canCreateNewEntry(Authentication authentication, @Valid NewEntryDTO newEntry) {
        List<String> allPublicWritableLogbookIds = logbookService.getAllIdsWriteAll();
        Set<String> requestedLogbookIds = logbookService.getLogbooksIdByNames(newEntry.logbooks());
        // check authenticated
        assertion(
                NotAuthorized
                        .notAuthorizedBuilder()
                        .errorCode(-1)
                        .errorDomain("EntryAuthorizationService::canCreateNewEntry")
                        .build(),
                // can write to all logbook
                () -> all
                        (
                                // this creates a list of supplier that return true if the user is authorized on logbook or if the logbook is public writable
                                requestedLogbookIds.stream()
                                        .map
                                                (
                                                        // return true if the user is authorized on logbook or if the logbook is public writable
                                                        logbookId -> (Supplier<Boolean>) () ->
                                                                any(
                                                                        () -> authService.checkAuthorizationForOwnerAuthTypeAndResourcePrefix(
                                                                                authentication,
                                                                                Write,
                                                                                "/logbook/%s".formatted(logbookId)
                                                                        ),
                                                                        () -> allPublicWritableLogbookIds.contains(logbookId)
                                                                )
                                                )
                                        .toList()
                        )
        );
        return true;
    }

    /**
     * Check if the user can create a new supersede entry
     *
     * @param authentication    the authentication object
     * @param entryId           the entry id to supersede
     * @param newSupersedeEntry the new supersede entry to create
     * @return true if the user can create the new supersede entry
     */
    public boolean canCreateSupersede(Authentication authentication, @NotNull String entryId, @Valid EntryNewDTO newSupersedeEntry) {
        List<String> allPublicWritableLogbookIds = logbookService.getAllIdsWriteAll();
        assertion(
                ResourceNotFound
                        .genericBuilder()
                        .errorCode(-1)
                        .errorDomain("EntryAuthorizationService::canCreateSupersede")
                        .build(),
                // can write to all logbook
                () -> all(
                        // write
                        newSupersedeEntry.logbooks().stream()
                                .map(
                                        (
                                                // return true if the user is authorized on logbook or if the logbook is public writable
                                                logbookId -> (Supplier<Boolean>) () ->
                                                        any(
                                                                () -> authService.checkAuthorizationForOwnerAuthTypeAndResourcePrefix(
                                                                        authentication,
                                                                        Write,
                                                                        "/logbook/%s".formatted(logbookId)
                                                                ),
                                                                () -> allPublicWritableLogbookIds.contains(logbookId)
                                                        )
                                        )
                                )
                                .toList()
                )
        );
        return true;
    }

    /**
     * Check if the user can create a new follow-up entry
     *
     * @param authentication   the authentication object
     * @param entryId          the entry id to follow-up
     * @param newFollowUpEntry the new follow-up entry to create
     * @return true if the user can create the new follow-up entry
     */
    public boolean canCreateNewFollowUp(Authentication authentication, @NotNull String entryId, @Valid EntryNewDTO newFollowUpEntry) {
        List<String> allPublicWritableLogbookIds = logbookService.getAllIdsWriteAll();
        assertion(
                ResourceNotFound
                        .genericBuilder()
                        .errorCode(-1)
                        .errorDomain("EntryAuthorizationService::canCreateNewFollowUp")
                        .build(),
                // can write on all logbook
                () -> all(
                        newFollowUpEntry.logbooks().stream()
                                .map(
                                        (
                                                // return true if the user is authorized on logbook or if the logbook is public writable
                                                logbookId -> (Supplier<Boolean>) () ->
                                                        any(
                                                                () -> authService.checkAuthorizationForOwnerAuthTypeAndResourcePrefix(
                                                                        authentication,
                                                                        Write,
                                                                        "/logbook/%s".formatted(logbookId)
                                                                ),
                                                                () -> allPublicWritableLogbookIds.contains(logbookId)
                                                        )
                                        )
                                )
                                .toList()
                )
        );
        return true;
    }

    /**
     * Check if the user can create a new follow-up entry
     *
     * @param authentication the authentication object
     * @param entryId        the entry id to follow-up
     * @return true if the user can create the new follow-up entry
     */
    public boolean canGetAllFollowUps(Authentication authentication, @NotNull String entryId) {
        // check for authorizations
        return true;
    }

    /**
     * Check if the user can get the full entry
     *
     * @param authentication the authentication object
     * @param entryId        the entry id to follow-up
     * @return true if the user can create the new follow-up entry
     */
    public boolean canGetFullEntry(Authentication authentication, @NotNull String entryId, AuthorizationCache authorizationCache) {
        // return all public readable logbook ids
        List<String> allPublicReadableLogbookIds = logbookService.getAllIdsReadAll();
        // check for authorizations
        List<String> lbForTheEntry = entryService.getLogbooksForAnEntryId(entryId);
        // contains all logbook that the entry belongs and the user can read
        Stream<String> authorizedIdStream = lbForTheEntry
                .stream()
                .filter
                        (
                                // filter only
                                lbId -> authService.checkAuthorizationForOwnerAuthTypeAndResourcePrefix
                                        (
                                                authentication,
                                                Read,
                                                "/logbook/%s".formatted(lbId)
                                        )
                        );

        authorizationCache.setAuthorizedLogbookId(
                Stream.concat(authorizedIdStream, allPublicReadableLogbookIds.stream())
                        .distinct()
                        .toList()
        );

        // check among all others authorizations
        assertion(
                ResourceNotFound
                        .genericBuilder()
                        .errorCode(-1)
                        .errorDomain("EntryAuthorizationService::canGetFullEntry")
                        .build(),
                //and
                () -> any(
                        // is root
                        () -> authService.checkForRoot(authentication),
                        // or is authorized at least in on e logbook to read
                        () -> !authorizationCache.getAuthorizedLogbookId().isEmpty()
                )
        );
        return true;
    }

    /**
     * Apply filter on the entryDTO to remove the logbook not authorized
     *
     * @param foundEntryResult   the result entry to filter
     * @param authentication     the authentication object
     * @param authorizationCache the authorization cache
     * @return true if the user can create the new follow-up entry
     */
    public boolean applyFilterAuthorizationEntryDTO(ApiResultResponse<EntryDTO> foundEntryResult, Authentication authentication, AuthorizationCache authorizationCache) {
        // the entry to clean
        EntryDTO entry = foundEntryResult.getPayload();
        //we have to filter out the logbook not authorized
        List<LogbookSummaryDTO> authorizedLogbookSummary = entry.logbooks()
                .stream()
                .filter(
                        lb -> authorizationCache.getAuthorizedLogbookId().contains(lb.id())
                ).toList();
        foundEntryResult.setPayload(entry.toBuilder().logbooks(authorizedLogbookSummary).build());
        return true;
    }

    /**
     * Check if the user can get the full entry
     *
     * @param authentication the authentication object
     * @param entryId        the entry id to follow-up
     * @return true if the user can create the new follow-up entry
     */
    public boolean canGetAllReferences(Authentication authentication, @NotNull String entryId, AuthorizationCache authorizationCache) {
        // the rule is the same as to get a full entry
        return canGetFullEntry(authentication, entryId, authorizationCache);
    }

    /**
     * Apply filter on the foundSummaries to remove the logbook not authorized
     *
     * @param foundSummaries     the result entry to filter
     * @param authentication     the authentication object
     * @param authorizationCache the authorization cache
     * @return true if the user can create the new follow-up entry
     */
    public boolean applyFilterAuthorizationOnEntrySummaryDTOList(ApiResultResponse<List<EntrySummaryDTO>> foundSummaries, Authentication authentication, AuthorizationCache authorizationCache) {
        // The entries to clean
        List<EntrySummaryDTO> entrySummaries = foundSummaries.getPayload();
        List<EntrySummaryDTO> updatedEntrySummaries = entrySummaries.stream()
                .map
                        (
                                entrySummary ->
                                {
                                    // Filter out the unauthorized logbooks
                                    List<LogbookSummaryDTO> authorizedLogbookSummary = entrySummary.logbooks()
                                            .stream()
                                            .filter(lb -> authorizationCache.getAuthorizedLogbookId().isEmpty() || authorizationCache.getAuthorizedLogbookId().contains(lb.id()))
                                            .toList();
                                    // filter out tags not authorized
                                    List<TagDTO> authorizedTag = entrySummary.tags()
                                            .stream()
                                            .filter(tag -> authorizationCache.getAuthorizedLogbookId().isEmpty() || authorizationCache.getAuthorizedLogbookId().contains(tag.logbook().id()))
                                            .toList();
                                    // Create a new EntrySummaryDTO with the filtered logbooks using toBuilder
                                    return entrySummary.toBuilder()
                                            .logbooks(authorizedLogbookSummary)
                                            .tags(authorizedTag)
                                            .build();
                                }
                        )
                .toList();
        foundSummaries.setPayload(updatedEntrySummaries);
        return true;
    }

    /**
     * Check if the user can search for entries
     *
     * @param authentication     the authentication object
     * @param logBooks           the logbooks to search
     * @param authorizationCache the authorization cache
     * @return true if the user can search for the entries
     */
    public boolean canSearchEntry(Authentication authentication, Optional<List<String>> logBooks, AuthorizationCache authorizationCache) {
        // return all public readable logbook ids
        List<String> allPublicReadableLogbookIds = logbookService.getAllIdsReadAll();

        // check authorization on
        assertion(
                NotAuthorized.notAuthorizedBuilder()
                        .errorCode(-1)
                        .errorDomain("EntryAuthorizationService::canSearchEntry")
                        .build(),
                // need to be authenticated
                () -> authService.checkAuthentication(authentication)
        );

        authorizationCache.setRootUser(authService.checkForRoot(authentication));
        if (!authorizationCache.getRootUser()) {
            // if user is not root we need to check for specific authorization
            List<String> allAuthorizedLogbookIds = authService.getAllAuthorizationForOwnerAndAndAuthTypeAndResourcePrefix(
                            authentication.getCredentials().toString(),
                            Read,
                            "/logbook/",
                            Optional.empty()
                    ).stream().map(
                            auth -> auth.resource().substring(
                                    auth.resource().lastIndexOf("/") + 1
                            )
                    )
                    .toList();

            // set the authorization cache
            var authorizedLogbook =
                    // join without duplicates all authorized and all public readable logbook
                    Stream.concat(allAuthorizedLogbookIds.stream(), allPublicReadableLogbookIds.stream())
                            .distinct()
                            .toList();

            // Check if the user has specified some logbooks
            if (logBooks.isPresent() && !logBooks.get().isEmpty()) {
                // give error if one of the logbook is not authorized
                List<String> finalAuthorizedLogbook = authorizedLogbook;
                logBooks.get().forEach(
                        lId -> {
                            if (!finalAuthorizedLogbook.contains(lId)) {
                                // notify the error on logbook authorization
                                var logbook = logbookService.getLogbook(lId);
                                throw ResourceNotFound.notFoundByTypeNameAndValue()
                                        .errorCode(-1)
                                        .resourceName("logbook")
                                        .resourceValue(logbook.name())
                                        .errorDomain("EntriesController::search")
                                        .build();
                            }
                        }
                );
                // at this time all logbook in logBooks are authorized
                authorizedLogbook = Stream.concat(authorizedLogbook.stream(), logBooks.get().stream())
                        .distinct()
                        .toList();
            }

            if (!authorizedLogbook.isEmpty()) {
                // cache the found authorized logbook
                authorizationCache.setAuthorizedLogbookId(authorizedLogbook);
            }
        } else {
            // if user is root we can use all logbook
            authorizationCache.setAuthorizedLogbookId(logBooks.orElse(Collections.emptyList()));
        }
        return true;
    }
}
