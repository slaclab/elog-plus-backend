package edu.stanford.slac.elog_plus.api.v1.controller;

import edu.stanford.slac.ad.eed.baselib.api.v1.dto.ApiResultResponse;
import edu.stanford.slac.ad.eed.baselib.api.v1.dto.PersonDTO;
import edu.stanford.slac.ad.eed.baselib.config.AppProperties;
import edu.stanford.slac.ad.eed.baselib.service.AuthService;
import edu.stanford.slac.ad.eed.baselib.service.PeopleGroupService;
import edu.stanford.slac.elog_plus.api.v1.dto.EntryDTO;
import edu.stanford.slac.elog_plus.api.v1.dto.EntryNewDTO;
import edu.stanford.slac.elog_plus.api.v1.dto.EntrySummaryDTO;
import edu.stanford.slac.elog_plus.api.v1.dto.QueryWithAnchorDTO;
import edu.stanford.slac.elog_plus.service.EntryService;
import edu.stanford.slac.elog_plus.service.LogbookService;
import edu.stanford.slac.elog_plus.service.authorization.AuthorizationCache;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static edu.stanford.slac.ad.eed.baselib.api.v1.dto.AuthorizationTypeDTO.Read;

@RestController()
@RequestMapping("/v1/entries")
@AllArgsConstructor
@Schema(description = "Main set of api for the query on the log entries")
public class EntriesController {
    final private PeopleGroupService peopleGroupService;
    final private AuthService authService;
    final private EntryService entryService;
    final private AppProperties appProperties;
    final private LogbookService logbookService;

    @PostMapping(produces = {MediaType.APPLICATION_JSON_VALUE})
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(description = "Create a new entry")
    @PreAuthorize("@baseAuthorizationService.checkAuthenticated(#authentication) and @entryAuthorizationService.canCreateNewEntry(#authentication, #newEntry)")
    public ApiResultResponse<String> newEntry(
            Authentication authentication,
            @Parameter(description = "The new entry to create", required = true)
            @RequestBody @Valid EntryNewDTO newEntry
    ) {
        PersonDTO creator = null;
        if (authentication.getCredentials().toString().endsWith(appProperties.getAuthenticationTokenDomain())) {
            // create fake person for authentication token
            creator = PersonDTO
                    .builder()
                    .gecos("Application Token")
                    .mail(authentication.getPrincipal().toString())
                    .build();
        } else {
            creator = peopleGroupService.findPerson(authentication);
        }
        return ApiResultResponse.of(
                entryService.createNew(
                        newEntry,
                        creator
                )
        );
    }

    @PostMapping(
            path = "/{entryId}/supersede",
            produces = {MediaType.APPLICATION_JSON_VALUE}
    )
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(description = "Create a new supersede for the log identified by the id")
    @PreAuthorize("@baseAuthorizationService.checkAuthenticated(#authentication) and @entryAuthorizationService.canCreateSupersede(#authentication, #entryId, #newSupersedeEntry)")
    public ApiResultResponse<String> newSupersede(
            Authentication authentication,
            @Parameter(description = "Is the id of the entry that will be superseded", required = true)
            @PathVariable @NotNull String entryId,
            @Parameter(description = "Is the new entry that will supersede the entry identified by the entryId", required = true)
            @RequestBody @Valid EntryNewDTO newSupersedeEntry) {
        PersonDTO creator = null;
        if (authentication.getCredentials().toString().endsWith(appProperties.getAuthenticationTokenDomain())) {
            // create fake person for authentication token
            creator = PersonDTO
                    .builder()
                    .gecos("Application Token")
                    .mail(authentication.getPrincipal().toString())
                    .build();
        } else {
            creator = peopleGroupService.findPerson(authentication);
        }
        return ApiResultResponse.of(
                entryService.createNewSupersede(entryId, newSupersedeEntry, creator)
        );
    }

    @PostMapping(
            path = "/{entryId}/follow-ups",
            produces = {MediaType.APPLICATION_JSON_VALUE}
    )
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(description = "Create a new follow-up log for the the log identified by the id")
    @PreAuthorize("@baseAuthorizationService.checkAuthenticated(#authentication) and @entryAuthorizationService.canCreateNewFollowUp(#authentication, #entryId, #newFollowUpEntry)")
    public ApiResultResponse<String> newFollowUp(
            Authentication authentication,
            @Parameter(description = "Is the id of the entry that will be followed-up", required = true)
            @PathVariable @NotNull String entryId,
            @Parameter(description = "Is the new entry that will follow-up the entry identified by the entryId", required = true)
            @RequestBody @Valid EntryNewDTO newFollowUpEntry) {
        return ApiResultResponse.of(
                entryService.createNewFollowUp(
                        entryId,
                        newFollowUpEntry,
                        peopleGroupService.findPerson(authentication)
                )
        );
    }

    @GetMapping(
            path = "/{entryId}/follow-ups",
            produces = {MediaType.APPLICATION_JSON_VALUE}
    )
    @ResponseStatus(HttpStatus.OK)
    @Operation(description = "Return all the follow-up logs for a specific entry identified by the id")
    @PreAuthorize("@baseAuthorizationService.checkAuthenticated(#authentication) and @entryAuthorizationService.canGetAllFollowUps(#authentication, #entryId)")
    public ApiResultResponse<List<EntrySummaryDTO>> getAllFollowUp(
            Authentication authentication,
            @Parameter(description = "Is the id of the entry for which we want to load all the follow-up")
            @PathVariable @NotNull String entryId) {
        // fetch all follow up
        return ApiResultResponse.of(
                filterEntrySummaryByAuthentication(
                        authentication,
                        entryService.getAllFollowUpForALog(entryId)
                )
        );
    }

    @GetMapping(
            path = "/{entryId}",
            produces = {MediaType.APPLICATION_JSON_VALUE}
    )
    @ResponseStatus(HttpStatus.OK)
    @Operation(description = "Return the full entry log information")
    @PreAuthorize("@baseAuthorizationService.checkAuthenticated(#authentication) and @entryAuthorizationService.canGetFullEntry(#authentication, #entryId, #authorizationCache)")
    @PostAuthorize("@entryAuthorizationService.applyFilterAuthorizationEntryDTO(returnObject, authentication, #authorizationCache)")
    public ApiResultResponse<EntryDTO> getFull(
            Authentication authentication,
            AuthorizationCache authorizationCache,
            @Parameter(description = "Is the id of the entry for which we want to load all the information")
            @PathVariable String entryId,
            @Parameter(name = "includeFollowUps", description = "If true the API return all the entries that are follow-up of this one")
            @RequestParam("includeFollowUps") Optional<Boolean> includeFollowUps,
            @Parameter(name = "includeFollowingUps", description = "If true the API return all the entries that are follow-up of this one")
            @RequestParam("includeFollowingUps") Optional<Boolean> includeFollowingUps,
            @Parameter(name = "includeHistory", description = "If true the API return all the entry updates history")
            @RequestParam("includeHistory") Optional<Boolean> includeHistory,
            @Parameter(name = "includeReferences", description = "If true the API return all the entries that are reference to this one")
            @RequestParam("includeReferences") Optional<Boolean> includeReferences,
            @Parameter(name = "includeReferencedBy", description = "If true the API return all the entries that are referenced byt this one")
            @RequestParam("includeReferencedBy") Optional<Boolean> includeReferencedBy,
            @Parameter(name = "includeSupersededBy", description = "If true the API return the entry that supersede this one")
            @RequestParam("includeSupersededBy") Optional<Boolean> includeSupersededBy
    ) {
        EntryDTO foundEntry = entryService.getFullEntry(
                entryId,
                includeFollowUps,
                includeFollowingUps,
                includeHistory,
                includeReferences,
                includeReferencedBy,
                includeSupersededBy
        );
        // return entry with authorized only logbook summary
        return ApiResultResponse.of(foundEntry);
    }

    @GetMapping(
            path = "/{entryId}/references",
            produces = {MediaType.APPLICATION_JSON_VALUE}
    )
    @ResponseStatus(HttpStatus.OK)
    @Operation(description = "Return all the references for a specific entry identified by the id")
    @PreAuthorize("@baseAuthorizationService.checkAuthenticated(#authentication) and @entryAuthorizationService.canGetAllReferences(#authentication, #entryId, #authorizationCache)")
    @PostAuthorize("@entryAuthorizationService.applyFilterAuthorizationOnEntrySummaryDTOList(returnObject, authentication, #authorizationCache)")
    public ApiResultResponse<List<EntrySummaryDTO>> getAllReferences(
            Authentication authentication,
            AuthorizationCache authorizationCache,
            @Parameter(description = "Is the id of the entry for which we want to load all the reference to")
            @PathVariable String entryId
    ) {
        return ApiResultResponse.of(
                filterEntrySummaryByAuthentication(
                        authentication,
                        entryService.getReferencesByEntryID(entryId)
                )
        );
    }

    @GetMapping(
            produces = {MediaType.APPLICATION_JSON_VALUE}
    )
    @ResponseStatus(HttpStatus.OK)
    @Operation(description = "Perform the query on all log data")
    @PreAuthorize("@baseAuthorizationService.checkAuthenticated(#authentication) and @entryAuthorizationService.canSearchEntry(#authentication, #logBooks, #authorizationCache)")
    @PostAuthorize("@entryAuthorizationService.applyFilterAuthorizationOnEntrySummaryDTOList(returnObject, authentication, #authorizationCache)")
    public ApiResultResponse<List<EntrySummaryDTO>> search(
            Authentication authentication,
            AuthorizationCache authorizationCache,
            @Parameter(name = "anchor", description = "Is the id of an entry from where start the search")
            @RequestParam("anchor") Optional<String> anchorId,
            @Parameter(name = "startDate", description = "Only include entries after this date. Defaults to current time.")
            @RequestParam("startDate") Optional<LocalDateTime> startDate,
            @Parameter(name = "endDate", description = "Only include entries before this date. If not supplied, then does not apply any filter")
            @RequestParam("endDate") Optional<LocalDateTime> endDate,
            @Parameter(name = "lastNShifts", description = "Show entries tat belong to the last N shifts. If this parameter is set, the startDate is ignored. The endDate if not specified will be the current time")
            @RequestParam("lasNShifts") Optional<Integer> lasNShifts,
            @Parameter(name = "contextSize", description = "Include this number of entries before the startDate (used for highlighting entries)")
            @RequestParam("contextSize") Optional<Integer> contextSize,
            @Parameter(name = "limit", description = "Limit the number the number of entries after the start date.")
            @RequestParam(value = "limit") Optional<Integer> limit,
            @Parameter(name = "search", description = "Typical search functionality")
            @RequestParam("search") Optional<String> search,
            @Parameter(name = "tags", description = "Only include entries that use one of these tags")
            @RequestParam("tags") Optional<List<String>> tags,
            @Parameter(name = "logbooks", description = "Only include entries that belong to one of these logbooks")
            @RequestParam("logbooks") Optional<List<String>> logBooks,
            @Parameter(name = "authors", description = "Only include entries that are authored by one of these users")
            @RequestParam("authors") Optional<List<String>> authors,
            @Parameter(name = "sortByLogDate", description = "Sort entries by log date instead event date")
            @RequestParam(value = "sortByLogDate", defaultValue = "false") Optional<Boolean> sortByLogDate,
            @Parameter(name = "hideSummaries", description = "Hide the summaries from the search(default is false)")
            @RequestParam(value = "hideSummaries", defaultValue = "false") Optional<Boolean> hideSummaries,
            @Parameter(name = "requireAllTags", description = "Require that all entries found includes all the tags")
            @RequestParam(value = "requireAllTags", defaultValue = "false") Optional<Boolean> requireAllTags,
            @Parameter(name = "originId", description = "Is the origin id of the source system record identification")
            @RequestParam(value = "originId") Optional<String> originId
    ) {
        return ApiResultResponse.of(
                entryService.findAll(
                        QueryWithAnchorDTO
                                .builder()
                                .anchorID(anchorId.orElse(null))
                                .startDate(startDate.orElse(null))
                                .endDate(endDate.orElse(null))
                                .lastNShifts(lasNShifts.orElse(null))
                                .contextSize(contextSize.orElse(0))
                                .limit(limit.orElse(0))
                                .search(search.orElse(null))
                                .tags(tags.orElse(Collections.emptyList()))
                                // if the logBooks is empty we should use all the authorized logbook
                                .logbooks(authorizationCache.getRootUser()?logBooks.orElse(Collections.emptyList()):logBooks.orElse(authorizationCache.getAuthorizedLogbookId()))
                                .authors(authors.orElse(null))
                                .sortByLogDate(sortByLogDate.orElse(false))
                                .hideSummaries(hideSummaries.orElse(false))
                                .requireAllTags(requireAllTags.orElse(false))
                                .originId(originId.orElse(null))
                                .build()
                )
        );
    }

    @GetMapping(
            path = "/{shiftId}/summaries/{date}",
            produces = {MediaType.APPLICATION_JSON_VALUE}
    )
    @ResponseStatus(HttpStatus.OK)
    @Operation(description = "Find the summary id for a specific shift and date")
    @PreAuthorize("@baseAuthorizationService.checkAuthenticated(#authentication)")
    public ApiResultResponse<String> findSummaryForShiftAndDate(
            Authentication authentication,
            @Parameter(name = "shiftId", description = "Is the id of the shift for which we want to find the summary")
            @PathVariable String shiftId,
            @Parameter(name = "date", description = "Is the date for which we want to find the summary")
            @PathVariable LocalDate date
    ) {
        return ApiResultResponse.of(
                entryService.findSummaryIdForShiftIdAndDate(
                        shiftId,
                        date
                )
        );
    }

    /**
     * Filter the entry summary by the authentication
     *
     * @param authentication the authentication to use for the filter
     * @param summaries       the list of entry summary to filter
     * @return the list of entry summary that are authorized for the authentication
     */
    private List<EntrySummaryDTO> filterEntrySummaryByAuthentication(
            Authentication authentication,
            List<EntrySummaryDTO> summaries
    ) {
        return summaries
                .stream()
                .map(
                        entry -> {
                            // filter all authorized logbook for en entry
                            var authorizedLogbook = entry
                                    .logbooks()
                                    .stream()
                                    .filter(
                                            lb -> authService.checkAuthorizationForOwnerAuthTypeAndResourcePrefix(
                                                    authentication,
                                                    Read,
                                                    "/logbook/%s".formatted(lb.id())
                                            )
                                    )
                                    .toList();
                            // recreate the summary with the authorized logbook
                            return entry.toBuilder()
                                    .logbooks(
                                            authorizedLogbook
                                    )
                                    .build();
                        }
                )
                .filter(
                        // remove all summary for which the user is unauthorized on all logbook
                        lb -> !lb.logbooks().isEmpty()
                )
                .toList();
    }
}
