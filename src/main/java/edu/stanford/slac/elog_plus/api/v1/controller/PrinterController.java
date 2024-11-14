package edu.stanford.slac.elog_plus.api.v1.controller;

import com.hp.jipp.encoding.IppInputStream;
import com.hp.jipp.encoding.IppOutputStream;
import com.hp.jipp.model.Operation;
import com.hp.jipp.model.Status;
import com.hp.jipp.trans.IppPacketData;
import edu.stanford.slac.ad.eed.baselib.api.v1.dto.ApiResultResponse;
import edu.stanford.slac.ad.eed.baselib.service.AuthService;
import edu.stanford.slac.elog_plus.api.v1.dto.AttachmentDTO;
import edu.stanford.slac.elog_plus.api.v1.dto.LogbookDTO;
import edu.stanford.slac.elog_plus.model.Attachment;
import edu.stanford.slac.elog_plus.service.AttachmentService;
import edu.stanford.slac.elog_plus.service.LogbookService;
import edu.stanford.slac.elog_plus.service.PrinterService;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static edu.stanford.slac.ad.eed.baselib.api.v1.dto.AuthorizationTypeDTO.Write;

@Log4j2
@Validated
@RestController()
@RequestMapping("/v1/printers")
@AllArgsConstructor
@Schema(description = "Api to support IPP printing for the entry creations")
public class PrinterController {
    AuthService authService;
    LogbookService logbookService;
    PrinterService printerService;

    @PostMapping(
            value = "/default",
            consumes = {"application/ipp"}
    )
    public ResponseEntity<byte[]> defaultPrinter(
            Authentication authentication,
            @RequestBody byte[] body
    ) throws IOException {
        IppPacketData response = null;
        byte[] returnBody = null;
        // check authentication
        try (IppInputStream inputStream = new IppInputStream(new ByteArrayInputStream(body))) {
            IppPacketData data = new IppPacketData(inputStream.readPacket(), inputStream);
            int operation = printerService.getPrintCommand(data);
            if ((authentication == null || !authentication.isAuthenticated()) &&
                    operation != Operation.Code.getPrinterAttributes) {
                // print job should be authenticated
                response = new IppPacketData(printerService.createErrorResponsePacket(data.getPacket(), Status.clientErrorNotAuthorized));
            } else {
                boolean authorized = true;
                LogbookDTO fullLogbook = null;
                if (operation == Operation.Code.printJob) {
                    // get the logbook where the entry should be created
                    String logbook = printerService.getLogbookDestination(data);
                    log.info("Printing to logbook {}", logbook);
                    fullLogbook = logbookService.getLogbookByName(logbook);
                    log.info("Fetch authorization on logbook {} with id {}", logbook, fullLogbook.id());
                    authorized = authService.checkAuthorizationForOwnerAuthTypeAndResourcePrefix(
                            authentication,
                            Write,
                            "/logbook/%s".formatted(fullLogbook.id())
                    );
                }
                if (!authorized) {
                    log.error("Not authorized to print to logbook {}", fullLogbook);
                    response = new IppPacketData(printerService.createErrorResponsePacket(data.getPacket(), Status.clientErrorNotAuthorized));
                } else {
                    log.info("Handling IPP packet");
                    response = printerService.handleIppPacket(data, fullLogbook);
                }
            }
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            IppOutputStream ippOutputStream = new IppOutputStream(outputStream);
            ippOutputStream.write(response.getPacket());
            InputStream extraData = response.getData();
            if (extraData != null) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = extraData.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                extraData.close();
            }
            returnBody = outputStream.toByteArray();
        } catch (IOException e) {
            log.error("Error processing IPP request", e);
            return ResponseEntity.badRequest().build();
        } finally {
            if (response != null) {
                response.close();
            }
        }

        return ResponseEntity.ok()
                .header("Content-Type", "application/ipp")
                .body(returnBody);


    }

    @PostMapping(
            value = "/attachment-queue",
            consumes = {"application/ipp"}
    )
    public ResponseEntity<byte[]> attachmentQueue(
            Authentication authentication,
            @RequestBody byte[] body
    ) throws IOException {
        IppPacketData response = null;
        byte[] returnBody = null;
        // check authentication
        try (IppInputStream inputStream = new IppInputStream(new ByteArrayInputStream(body))) {
            IppPacketData data = new IppPacketData(inputStream.readPacket(), inputStream);
            int operation = printerService.getPrintCommand(data);
            if ((authentication == null || !authentication.isAuthenticated()) &&
                    operation != Operation.Code.getPrinterAttributes) {
                // print job should be authenticated
                response = new IppPacketData(printerService.createErrorResponsePacket(data.getPacket(), Status.clientErrorNotAuthorized));
            } else {
                boolean authorized = true;
                if (!authorized) {
                    response = new IppPacketData(printerService.createErrorResponsePacket(data.getPacket(), Status.clientErrorNotAuthorized));
                } else {
                    response = printerService.handleIppPacketForAttachmentQueue("v1/printer/attachment-queue", data);
                }
            }
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            IppOutputStream ippOutputStream = new IppOutputStream(outputStream);
            ippOutputStream.write(response.getPacket());
            InputStream extraData = response.getData();
            if (extraData != null) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = extraData.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                extraData.close();
            }
            returnBody = outputStream.toByteArray();
        } catch (IOException e) {
            log.error("Error processing IPP request", e);
            return ResponseEntity.badRequest().build();
        } finally {
            if (response != null) {
                response.close();
            }
        }

        return ResponseEntity.ok()
                .header("Content-Type", "application/ipp")
                .body(returnBody);


    }
}
