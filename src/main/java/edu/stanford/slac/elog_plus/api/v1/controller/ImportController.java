package edu.stanford.slac.elog_plus.api.v1.controller;

import edu.stanford.slac.elog_plus.api.v1.dto.ApiResultResponse;
import edu.stanford.slac.elog_plus.api.v1.dto.EntryImportDTO;
import edu.stanford.slac.elog_plus.exception.ControllerLogicException;
import edu.stanford.slac.elog_plus.model.FileObjectDescription;
import edu.stanford.slac.elog_plus.service.ImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RestController()
@RequestMapping("/v1/import")

@Log4j2
@AllArgsConstructor
@Schema(description = "Main set of api for inject data into ELog system")
public class ImportController {
    ImportService importService;

    @Operation(description = "Upload data for import an entry")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(
            consumes = {MediaType.MULTIPART_FORM_DATA_VALUE},
            produces = {MediaType.APPLICATION_JSON_VALUE}
    )
    public ApiResultResponse<String> uploadEntryAndAttachment(
            @Parameter(schema = @Schema(type = "string", implementation = EntryImportDTO.class))
            @RequestPart("entry") @Valid EntryImportDTO entryToImport,
            @RequestPart(value = "files", required = false)
            MultipartFile[] files) throws IOException {
        log.info("[import %s] manage attachment".formatted(entryToImport.text()));
        List<FileObjectDescription> attachmentList = new ArrayList<>();
        if (files != null) {
            attachmentList = Arrays.stream(files).map(
                    file -> {
                        try {
                            return FileObjectDescription
                                    .builder()
                                    .fileName(
                                            file.getOriginalFilename()
                                    )
                                    .contentType(
                                            file.getContentType()
                                    )
                                    .is(
                                            file.getInputStream()
                                    )
                                    .build();
                        } catch (IOException e) {
                            throw ControllerLogicException.of(
                                    -1,
                                    e.getMessage(),
                                    "ImportController:uploadEntryAndAttachment"
                            );
                        }
                    }
            ).collect(Collectors.toList());
        }

        // convert the tags
        log.info("[import %s] tags and logbook conversion".formatted(entryToImport.text()));
        entryToImport = entryToImport.toBuilder()
                .logbooks(
                        importService.getLogbooksIdsByNames(entryToImport.logbooks())
                )
                .tags(
                        importService.ensureTagsNamesOnAllLogbooks(entryToImport.tags(), entryToImport.logbooks())
                )
                .build();
        return ApiResultResponse.of
                (
                        importService.importSingleEntry
                                (
                                        entryToImport,
                                        attachmentList
                                )
                );
    }
}
