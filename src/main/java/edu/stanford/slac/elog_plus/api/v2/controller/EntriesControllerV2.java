package edu.stanford.slac.elog_plus.api.v2.controller;

import edu.stanford.slac.ad.eed.baselib.api.v1.dto.ApiResultResponse;
import edu.stanford.slac.ad.eed.baselib.api.v1.dto.PersonDTO;
import edu.stanford.slac.ad.eed.baselib.config.AppProperties;
import edu.stanford.slac.ad.eed.baselib.service.PeopleGroupService;
import edu.stanford.slac.elog_plus.api.v1.dto.EntryNewDTO;
import edu.stanford.slac.elog_plus.api.v2.dto.NewEntryDTO;
import edu.stanford.slac.elog_plus.service.EntrySimplifiedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController()
@RequestMapping("/v2/entries")
@AllArgsConstructor
@Schema(description = "Advanced high level set of api for entry manipulation")
public class EntriesControllerV2 {
    final private AppProperties appProperties;
    final private PeopleGroupService peopleGroupService;
    final private EntrySimplifiedService entrySimplifiedService;
    @PostMapping(
            consumes = {MediaType.MULTIPART_FORM_DATA_VALUE},
            produces = {MediaType.APPLICATION_JSON_VALUE}
    )
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(description = "Create a new entry with attachment using a single post")
    @PreAuthorize("@baseAuthorizationService.checkAuthenticated(#authentication) and @entryAuthorizationService.canCreateNewEntry(#authentication, #newEntry)")
    public ApiResultResponse<String> newEntryWithAttachment(
            Authentication authentication,
            @Parameter(schema = @Schema(type = "string", implementation = NewEntryDTO.class))
            @RequestPart(value = "entry") @Valid NewEntryDTO newEntry,
            @RequestPart(value = "files", required = false)
            MultipartFile[] files
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
                entrySimplifiedService.createNew(
                        creator,
                        newEntry,
                        files
                )
        );
    }
}
