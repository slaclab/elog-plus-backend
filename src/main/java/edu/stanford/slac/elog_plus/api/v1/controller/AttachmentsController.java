package edu.stanford.slac.elog_plus.api.v1.controller;

import edu.stanford.slac.ad.eed.baselib.api.v1.dto.ApiResultResponse;
import edu.stanford.slac.ad.eed.baselib.exception.NotAuthorized;
import edu.stanford.slac.ad.eed.baselib.service.AuthService;
import edu.stanford.slac.elog_plus.api.v1.dto.AttachmentDTO;
import edu.stanford.slac.elog_plus.api.v1.dto.EntrySummaryDTO;
import edu.stanford.slac.elog_plus.api.v1.dto.LogbookSummaryDTO;
import edu.stanford.slac.elog_plus.model.FileObjectDescription;
import edu.stanford.slac.elog_plus.service.AttachmentService;
import edu.stanford.slac.elog_plus.service.EntryService;
import edu.stanford.slac.elog_plus.service.PrinterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static edu.stanford.slac.ad.eed.baselib.api.v1.dto.AuthorizationTypeDTO.Read;
import static edu.stanford.slac.ad.eed.baselib.api.v1.dto.AuthorizationTypeDTO.Write;
import static edu.stanford.slac.ad.eed.baselib.exception.Utility.assertion;

@RestController()
@RequestMapping("/v1/attachment")
@AllArgsConstructor
@Schema(description = "Set of api for attachment manipulation")
public class AttachmentsController {
    AuthService authService;
    EntryService entryService;
    AttachmentService attachmentService;

    @PostMapping(
            consumes = {"multipart/form-data"},
            produces = {MediaType.APPLICATION_JSON_VALUE}
    )
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new attachment")
    @PreAuthorize("@baseAuthorizationService.checkAuthenticated(#authentication) and @attachmentAuthorizationService.canCreate(#authentication)")
    public ApiResultResponse<String> newAttachment(
            Authentication authentication,
            @Parameter(name = "uploadFile", description = "The file to upload", required = true)
            @RequestParam("uploadFile") MultipartFile uploadFile
    ) throws Exception {
        // check if the user is authenticated
        FileObjectDescription desc = FileObjectDescription
                .builder()
                .fileName(
                        uploadFile.getOriginalFilename()
                )
                .contentType(
                        uploadFile.getContentType()
                )
                .is(
                        uploadFile.getInputStream()
                )
                .build();
        return ApiResultResponse.of(
                attachmentService.createAttachment(desc, true)
        );
    }

    @GetMapping(
            path = "/{attachmentId}/download"
            //produces = {MediaType.APPLICATION_OCTET_STREAM_VALUE}
    )
    @Operation(summary = "Load an attachment using an unique attachment id")
    @PreAuthorize("@baseAuthorizationService.checkAuthenticated(#authentication)")// and @attachmentAuthorizationService.canRead(#authentication, #attachmentId)
    public ResponseEntity<Resource> download(
            Authentication authentication,
            @Parameter(name = "attachmentId", description = "The unique id of the attachment", required = true)
            @PathVariable @NotNull String attachmentId
    ) throws Exception {
        FileObjectDescription desc = attachmentService.getAttachmentContent(attachmentId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf(desc.getContentType()));
        headers.setContentDisposition(
                ContentDisposition
                        .inline()
                        .filename(desc.getFileName(), StandardCharsets.UTF_8)
                        .build()
        );
        return new ResponseEntity<>(new InputStreamResource(desc.getIs()), headers, HttpStatus.OK);
    }

    @GetMapping(
            path = "/{attachmentId}/preview.jpg"
            //produces = {MediaType.APPLICATION_OCTET_STREAM_VALUE}
    )
    @Operation(summary = "Load an attachment preview using an unique attachment id")
    @PreAuthorize("@baseAuthorizationService.checkAuthenticated(#authentication)")// and @attachmentAuthorizationService.canRead(#authentication, #attachmentId)
    public ResponseEntity<Resource> downloadPreview(
            Authentication authentication,
            @Parameter(name = "attachmentId", description = "The unique id of the attachment", required = true)
            @PathVariable String attachmentId
    ) throws Exception {
        FileObjectDescription desc = attachmentService.getPreviewContent(attachmentId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf(desc.getContentType()));
        headers.setContentDisposition(
                ContentDisposition
                        .inline()
                        .filename(desc.getFileName(), StandardCharsets.UTF_8)
                        .build()
        );
        headers.setCacheControl(CacheControl.maxAge(15552000, TimeUnit.SECONDS).cachePublic());
        return new ResponseEntity<>(new InputStreamResource(desc.getIs()), headers, HttpStatus.OK);
    }

    @GetMapping(
            value = "/queued",
            produces = {"application/json"}
    )
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("@baseAuthorizationService.checkAuthenticated(#authentication)")
    public ApiResultResponse<List<AttachmentDTO>> findAllQueuedAttachments(
            Authentication authentication
    ) {
        return ApiResultResponse.of(attachmentService.findAllByReferenceInfo(AttachmentService.ATTACHMENT_QUEUED_REFERENCE));
    }
}
