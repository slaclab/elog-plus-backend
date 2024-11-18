package edu.stanford.slac.elog_plus.service;

import com.hp.jipp.encoding.IppPacket;
import com.hp.jipp.encoding.NameType;
import com.hp.jipp.model.*;
import com.hp.jipp.trans.IppPacketData;
import edu.stanford.slac.ad.eed.baselib.api.v1.dto.PersonDTO;
import edu.stanford.slac.ad.eed.baselib.config.AppProperties;
import edu.stanford.slac.ad.eed.baselib.exception.ControllerLogicException;
import edu.stanford.slac.ad.eed.baselib.service.PeopleGroupService;
import edu.stanford.slac.elog_plus.api.v1.dto.EntryNewDTO;
import edu.stanford.slac.elog_plus.api.v1.dto.LogbookDTO;
import edu.stanford.slac.elog_plus.config.ELOGAppProperties;
import edu.stanford.slac.elog_plus.model.FileObjectDescription;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.detect.Detector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.hp.jipp.encoding.Tag.*;
import static io.jsonwebtoken.lang.Collections.emptyList;
import static io.jsonwebtoken.lang.Collections.emptySet;

@Log4j2
@Service
@AllArgsConstructor
public class PrinterService {
    private final ELOGAppProperties elogAppProperties;
    private final AppProperties appProperties;
    private final PeopleGroupService peopleGroupService;
    private final EntryService entryService;
    private final AttachmentService attachmentService;

    /**
     * Get the print command from the IPP packet
     *
     * @param inputData The IPP packet data
     * @return The print command
     */
    public int getPrintCommand(IppPacketData inputData) {
        return inputData.getPacket().getOperation().getCode();
    }

    /**
     * Get the logbook destination from the IPP packet
     *
     * @param inputData The IPP packet data
     * @return The logbook destination
     */
    public String getLogbookDestination(IppPacketData inputData) {
        return inputData.getPacket().getString(jobAttributes, new NameType.Set("logbook"));
    }

    /**
     * Get the file name from the IPP packet
     *
     * @param packet The IPP packet
     * @return The file name
     */
    public String getFileName(IppPacket packet) {
        return packet.getString(jobAttributes, new NameType.Set("document-name-supplied"));

    }

    /**
     * Process an IPP packet
     *
     * @param inputData The IPP packet data
     * @return The IPP packet data
     */
    public IppPacketData handleIppPacket(IppPacketData inputData, LogbookDTO logbook) {
        IppPacket requestPacket = inputData.getPacket();
        log.info("Processing IPP request: " + requestPacket);

        IppPacket responsePacket;
        responsePacket = switch (getPrintCommand(inputData)) {
            case Operation.Code.printJob -> handlePrintDocument(requestPacket, inputData.getData(), logbook);
            case Operation.Code.getPrinterAttributes -> handleGetPrinterAttributes(requestPacket);
            default -> createErrorResponsePacket(requestPacket, Status.clientErrorNotFetchable);
        };

        return new IppPacketData(responsePacket, null);
    }


    public IppPacketData handleIppPacketForAttachmentQueue(String printerURI, IppPacketData inputData) {
        IppPacket requestPacket = inputData.getPacket();
        log.info("Processing IPP request: " + requestPacket);

        IppPacket responsePacket;
        responsePacket = switch (getPrintCommand(inputData)) {
            case Operation.Code.printJob -> handleAttachmentQueue(printerURI, requestPacket, inputData.getData());
            case Operation.Code.getPrinterAttributes -> handleGetPrinterAttributes(requestPacket);
            default -> createErrorResponsePacket(requestPacket, Status.clientErrorNotFetchable);
        };

        return new IppPacketData(responsePacket, null);
    }


    /**
     * Create an error response packet
     *
     * @param requestPacket The request packet
     * @param status        The status
     * @return The error response packet
     */
    public IppPacket createErrorResponsePacket(IppPacket requestPacket, Status status) {
        return IppPacket.response(status).build();
    }


    /**
     * Handle a Print-Job request
     *
     * @param requestPacket  The IPP request packet
     * @param documentStream The document stream
     * @return The IPP response packet
     */
    private IppPacket handlePrintDocument(IppPacket requestPacket, InputStream documentStream, LogbookDTO logbook) {
        log.info("Received Print-Job request for logbook {}", logbook.name());
        // Fetch the document from the IPP packet
        IppPacket responsePacket = null;
        TikaConfig config = TikaConfig.getDefaultConfig();
        Detector detector = config.getDetector();
        Metadata metadata = new Metadata();
        try {
            if (documentStream == null) {
                // return error
                return IppPacket.jobResponse(
                                Status.clientErrorBadRequest,
                                requestPacket.getRequestId(),
                                URI.create("/v1/printers/defaults/jobs/1"),
                                JobState.aborted,
                                Collections.singletonList(JobStateReason.jobQueued))
                        .putAttributes(operationAttributes, Types.printerUri.of(URI.create("/v1/printers/defaults")))
                        .build();
            }
            MediaType mediaType = detector.detect(documentStream, metadata);
            if (mediaType == null) {
                // return error
                return IppPacket.jobResponse(
                                Status.clientErrorBadRequest,
                                requestPacket.getRequestId(),
                                URI.create("/v1/printers/defaults/jobs/1"),
                                JobState.aborted,
                                Collections.singletonList(JobStateReason.jobQueued))
                        .putAttributes(operationAttributes, Types.printerUri.of(URI.create("/v1/printers/defaults")))
                        .build();
            }

            switch (mediaType.getBaseType().getType()) {
                case "text" -> handleTextBaseType(requestPacket, logbook, mediaType, documentStream);
                case "image" -> handleImageBaseType(requestPacket, logbook, mediaType, documentStream);
                case "application" -> handleApplicationBaseType(requestPacket, logbook, mediaType, documentStream);
                default -> {
                    return new IppPacket(Status.clientErrorBadRequest, requestPacket.getRequestId());
                }
            }

            responsePacket = IppPacket.jobResponse(
                            Status.successfulOk,
                            requestPacket.getRequestId(),
                            URI.create("/v1/printers/defaults/jobs/1"),
                            JobState.pending,
                            Collections.singletonList(JobStateReason.jobQueued))
                    .putAttributes(operationAttributes, Types.printerUri.of(URI.create("/v1/printers/defaults")))
                    .build();
        } catch (IOException e) {
            log.error("Error processing print job {}", e.toString());
            responsePacket = new IppPacket(Status.clientErrorBadRequest, requestPacket.getRequestId());
        }
        // Return the response packet
        return responsePacket;
    }

    /**
     * Handle the attachment queue
     *
     * @param requestPacket The IPP request packet
     * @return The IPP response packet
     */
    private IppPacket handleAttachmentQueue(String printerURI, IppPacket requestPacket, InputStream documentStream) {
        IppPacket responsePacket = null;
        log.info("Received Print-Job request for attachment queue");
        TikaConfig config = TikaConfig.getDefaultConfig();
        Detector detector = config.getDetector();
        Metadata metadata = new Metadata();
        try {
            if (documentStream == null) {
                // return error
                return IppPacket.jobResponse(
                                Status.clientErrorBadRequest,
                                requestPacket.getRequestId(),
                                URI.create("%s/jobs/1".formatted(printerURI)),
                                JobState.aborted,
                                Collections.singletonList(JobStateReason.jobQueued))
                        .putAttributes(operationAttributes, Types.printerUri.of(URI.create(printerURI)))
                        .build();
            }
            MediaType mediaType = detector.detect(documentStream, metadata);
            if (mediaType == null) {
                // return error
                return IppPacket.jobResponse(
                                Status.clientErrorBadRequest,
                                requestPacket.getRequestId(),
                                URI.create("%s/jobs/1".formatted(printerURI)),
                                JobState.aborted,
                                Collections.singletonList(JobStateReason.jobQueued))
                        .putAttributes(operationAttributes, Types.printerUri.of(URI.create(printerURI)))
                        .build();
            }

            switch (mediaType.getBaseType().getType()) {
                case "image", "application" -> {
                    String attachmentId = crateAttachmentAttachmentQueueElement(requestPacket, mediaType, documentStream);

                    // if all is gone as it should at this point we can create the entry
                    if (attachmentId == null) {
                        throw ControllerLogicException.builder()
                                .errorCode(-1)
                                .errorMessage("Error creating attachment")
                                .errorDomain("PrinterService::handleApplicationBaseType")
                                .build();
                    }
                }
                default -> {
                    return new IppPacket(Status.clientErrorBadRequest, requestPacket.getRequestId());
                }
            }

            responsePacket = IppPacket.jobResponse(
                            Status.successfulOk,
                            requestPacket.getRequestId(),
                            URI.create("%s/jobs/1".formatted(printerURI)),
                            JobState.pending,
                            Collections.singletonList(JobStateReason.jobQueued))
                    .putAttributes(operationAttributes, Types.printerUri.of(URI.create(printerURI)))
                    .build();
        } catch (IOException e) {
            log.error("Error processing print job {}", e.toString());
            responsePacket = new IppPacket(Status.clientErrorBadRequest, requestPacket.getRequestId());
        }
        return responsePacket;
    }

    /**
     * Handle an application base type
     *
     * @param requestPacket  The IPP request packet
     * @param logbookDTO     The logbook DTO
     * @param type           The media type
     * @param documentStream The document stream
     */
    private void handleApplicationBaseType(IppPacket requestPacket, LogbookDTO logbookDTO, MediaType type, InputStream documentStream) throws IOException {
        log.info("Create entry form application print request for subtype: {}", type.getSubtype());
        if (!type.getSubtype().equals("pdf") && !type.getSubtype().equals("postscript")) {
            throw ControllerLogicException.builder()
                    .errorCode(-1)
                    .errorMessage("Unsupported document type")
                    .errorDomain("PrinterService::handleApplicationBaseType")
                    .build();
        }
        log.info("Create entry form pdf print request");
        // create attachment first, then we can create entry that own the attachment
        String attachmentId = crateAttachment(requestPacket, logbookDTO, type, documentStream);

        // if all is gone as it should at this point we can create the entry
        if (attachmentId == null) {
            throw ControllerLogicException.builder()
                    .errorCode(-1)
                    .errorMessage("Error creating attachment")
                    .errorDomain("PrinterService::handleApplicationBaseType")
                    .build();
        }

        // create entry
        createEntry(requestPacket, logbookDTO, null, Set.of(attachmentId));
    }

    /**
     * Handle an image base type
     *
     * @param requestPacket  The IPP request packet
     * @param logbookDTO     The logbook DTO
     * @param type           The media type
     * @param documentStream The document stream
     */
    private void handleImageBaseType(IppPacket requestPacket, LogbookDTO logbookDTO, MediaType type, InputStream documentStream) throws IOException {
        log.info("Create entry form image print request for subtype: {}", type);
        // create attachment first, then we can create entry that own the attachment
        String attachmentId = crateAttachment(requestPacket, logbookDTO, type, documentStream);

        // if all is gone as it should at this point we can create the entry
        if (attachmentId == null) {
            throw ControllerLogicException.builder()
                    .errorCode(-1)
                    .errorMessage("Error creating attachment")
                    .errorDomain("PrinterService::handleImageBaseType")
                    .build();
        }
        // create entry
        createEntry(requestPacket, logbookDTO, null, Set.of(attachmentId));
    }


    /**
     * Handle a text base type
     *
     * @param requestPacket  The IPP request packet
     * @param logbookDTO     The logbook DTO
     * @param type           The media type
     * @param documentStream The document stream
     * @throws IOException If an error occurs
     */
    private void handleTextBaseType(IppPacket requestPacket, LogbookDTO logbookDTO, MediaType type, InputStream documentStream) throws IOException {
        log.info("Create entry form text document print request for subtype: {}", type.getSubtype());
        StringBuilder documentContent = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(documentStream, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            documentContent.append(line).append("\n");
        }
        createEntry(requestPacket, logbookDTO, documentContent, emptySet());
    }


    /**
     * Create an entry
     *
     * @param requestPacket   The IPP request packet
     * @param logbookDTO      The logbook DTO
     * @param documentContent The document content
     * @throws IOException If an error occurs
     */
    private void createEntry(IppPacket requestPacket, LogbookDTO logbookDTO, StringBuilder documentContent, Set<String> attachmentId) throws IOException {
        String documentFileName = getFileName(requestPacket);
        PersonDTO creator = null;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // we have all the document
        if (auth.getCredentials().toString().endsWith(appProperties.getAuthenticationTokenDomain())) {
            // create fake person for authentication token
            creator = PersonDTO
                    .builder()
                    .gecos("Application Token")
                    .mail(auth.getPrincipal().toString())
                    .build();
        } else {
            creator = peopleGroupService.findPerson(auth);
        }
        entryService.createNew(
                EntryNewDTO.builder()
                        .logbooks(Set.of(logbookDTO.id()))
                        .title(documentFileName == null ? "Printed Text Document" : documentFileName)
                        .text((documentContent != null && !documentContent.isEmpty()) ? documentContent.toString() : "")
                        .attachments(attachmentId)
                        .build(),
                creator
        );
    }

    /**
     * Create an attachment
     *
     * @param requestPacket  The IPP request packet
     * @param logbookDTO     The logbook DTO
     * @param type           The media type
     * @param documentStream The document stream
     */
    private String crateAttachment(IppPacket requestPacket, LogbookDTO logbookDTO, MediaType type, InputStream documentStream) {
        log.info("Create attachment from image print request for subtype: {}", type.getSubtype());
        String documentFileName = getFileName(requestPacket);
        return attachmentService.createAttachment(
                FileObjectDescription.builder()
                        .contentType(type.toString())
                        .fileName(documentFileName == null ? "Printed Document" : documentFileName)
                        .is(documentStream)
                        .build(),
                true
        );
    }

    /**
     * Create an attachment
     *
     * @param requestPacket  The IPP request packet
     * @param type           The media type
     * @param documentStream The document stream
     */
    private String crateAttachmentAttachmentQueueElement(IppPacket requestPacket, MediaType type, InputStream documentStream) {
        log.info("Create attachment for attachmentQueue from image print request for subtype: {}", type.getSubtype());
        String documentFileName = getFileName(requestPacket);
        return attachmentService.createAttachment(
                FileObjectDescription.builder()
                        .contentType(type.toString())
                        .fileName(documentFileName == null ? "Printed Document" : documentFileName)
                        .is(documentStream)
                        .build(),
                true,
                Optional.of(AttachmentService.ATTACHMENT_QUEUED_REFERENCE)
        );
    }

    /**
     * Handle a Get-Printer-Attributes request
     *
     * @param requestPacket The IPP request packet
     * @return The IPP response packet
     */
    private IppPacket handleGetPrinterAttributes(IppPacket requestPacket) {
        return IppPacket
                .response(Status.successfulOk)
                .putAttributes(
                        operationAttributes,
                        Types.printerUri.of(getPrinterUri()),
                        Types.attributesCharset.of("utf-8"),
                        Types.attributesNaturalLanguage.of("en")
                )
                .putAttributes(
                        printerAttributes,
                        Types.printerUriSupported.of(getPrinterUri()),
                        Types.printerName.of("ElogIPPPrinterInterface"),
                        Types.printerState.of(PrinterState.idle),
                        Types.printerLocation.of(getPrinterUri().toString()),
                        Types.printerStateReasons.of("none"),
                        Types.operationsSupported.of(
                                Operation.printJob,
                                Operation.getJobAttributes,
                                Operation.getPrinterAttributes
                        ),
                        Types.documentFormatSupported.of(
                                "application/pdf",
                                "application/postscript",
                                "image/gif",
                                "image/jpeg",
                                "image/pjpeg",
                                "image/png",
                                "image/svg+xml",
                                "image/tiff",
                                "text/plain"
                        )
                )
                .build();
    }

    private URI getPrinterUri() {
        return URI.create("%s/v1/printers/default".formatted(elogAppProperties.getIppUriPrefix()));
    }

    private URI getPrinterUri(String postfix) {
        return URI.create("%s/v1/printers/default/%s".formatted(elogAppProperties.getIppUriPrefix(), postfix));
    }

}
