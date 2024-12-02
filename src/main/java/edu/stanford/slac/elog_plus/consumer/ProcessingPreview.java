package edu.stanford.slac.elog_plus.consumer;

import edu.stanford.slac.elog_plus.model.Attachment;
import edu.stanford.slac.elog_plus.model.FileObjectDescription;
import edu.stanford.slac.elog_plus.repository.EntryRepository;
import edu.stanford.slac.elog_plus.repository.StorageRepository;
import edu.stanford.slac.elog_plus.service.AttachmentService;
import io.micrometer.core.instrument.Counter;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.tasks.UnsupportedFormatException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

@Log4j2
@Component
@AllArgsConstructor
public class ProcessingPreview {
    final private EntryRepository   entryRepository;
    final private AttachmentService attachmentService;
    final private StorageRepository storageRepository;
    final private Counter previewProcessedCounter;
    final private Counter previewErrorsCounter;

    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 2_000, maxDelay = 10_000, multiplier = 2),
            autoCreateTopics = "false",
            kafkaTemplate = "attachmentKafkaTemplate"
    )
    @KafkaListener(
            topics = "${edu.stanford.slac.elog-plus.image-preview-topic}",
            containerFactory = "attachmentKafkaListenerContainerFactory"
    )
    public void processPreview(
            Attachment attachment,
            Acknowledgment acknowledgment,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset
    ) throws RuntimeException, IOException {
        log.info("Process preview for attachment: {} from {} @ {}", attachment, topic, offset);
        byte[] imageBytes = null;
        FileObjectDescription fod = null;
        try {
            attachmentService.setPreviewProcessingState(attachment.getId(), Attachment.PreviewProcessingState.Processing);
            fod = attachmentService.getAttachmentContent(attachment.getId());
            String previewID = String.format("%s-preview", attachment.getId());

            if (attachment.getContentType().compareToIgnoreCase("application/pdf") == 0) {
                imageBytes = getFromPDF(fod.getIs());
            } else if (
                    attachment.getContentType().compareToIgnoreCase("application/ps") == 0 ||
                            attachment.getContentType().compareToIgnoreCase("application/postscript") == 0) {
                imageBytes = getFromPS(fod.getIs());
            } else {
                imageBytes = fod.getIs().readAllBytes();
            }
            // crete preview
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Thumbnails.of(new ByteArrayInputStream(imageBytes))
                    .width(1024)
                    .outputFormat("jpg")
                    .toOutputStream(baos);
            storageRepository.uploadFile(
                    previewID,
                    FileObjectDescription
                            .builder()
                            .fileName(previewID)
                            .contentType(MediaType.IMAGE_JPEG_VALUE)
                            .is(new ByteArrayInputStream(baos.toByteArray()))
                            .build()
            );
            baos = new ByteArrayOutputStream();
            Thumbnails.of(new ByteArrayInputStream(imageBytes))
                    .size(32, 32)
                    .outputFormat("jpg")
                    .toOutputStream(baos);
            attachmentService.setMiniPreview(attachment.getId(), baos.toByteArray());
            attachmentService.setPreviewID(attachment.getId(), previewID);
            attachmentService.setPreviewProcessingState(attachment.getId(), Attachment.PreviewProcessingState.Completed);

            previewProcessedCounter.increment();
            acknowledgment.acknowledge();
        } catch (UnsupportedFormatException e) {
            attachmentService.setPreviewProcessingState(attachment.getId(), Attachment.PreviewProcessingState.PreviewNotAvailable);
            // in this case we manage this error with the state of image not available
            log.info("Unsupported image for preview for the attachment {}", attachment);
            previewErrorsCounter.increment();
        } catch (Throwable e) {
            attachmentService.setPreviewProcessingState(attachment.getId(), Attachment.PreviewProcessingState.Error);
            log.error("Error during preview generation for the attachment {} with error with message '{}' - [{}]", attachment, e.getMessage(), e);
            previewErrorsCounter.increment();
            throw new RuntimeException(e);
        } finally {
            if (fod != null && fod.getIs() != null) {
                fod.getIs().close();
            }
        }
    }

    /**
     * Get the first page of a PS file as a JPEG image
     *
     * @param is the input stream
     * @return the JPEG image as a byte array
     * @throws IOException if an error occurs during the conversion
     * @throws InterruptedException if the process is interrupted
     */
    private byte[] getFromPS(InputStream is) throws IOException, InterruptedException {
        byte[] imageBytes = null;
        Path tmpPSFilePath = null;
        Path tmpJPEGFilePath = null;
        try {
            // Temporary paths for PostScript and JPEG files
            tmpPSFilePath = Files.createTempFile("psTempFile_%s".formatted(UUID.randomUUID()), ".ps");
            tmpJPEGFilePath = Files.createTempFile("jpegTempFile_%s".formatted(UUID.randomUUID()), ".jpg");

            // Write the input PostScript data to the temporary file
            Files.write(tmpPSFilePath, is.readAllBytes(), StandardOpenOption.WRITE);

            // Build Ghostscript command for JPEG conversion
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "gs",
                    "-dNOPAUSE",
                    "-dBATCH",
                    "-sDEVICE=jpeg",
                    "-r300", // Set resolution (adjust as needed)
                    "-dJPEGQ=95", // Set JPEG quality (1-100)
                    "-sOutputFile=" + tmpJPEGFilePath.toString(),
                    tmpPSFilePath.toString()
            );

            // Execute the Ghostscript process
            Process process = processBuilder.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new IOException("Ghostscript failed to convert PS to JPEG. Exit code: " + exitCode);
            }

            // Read the generated JPEG into a byte array
            imageBytes = Files.readAllBytes(tmpJPEGFilePath);

        } finally {
            // Clean up temporary files
            if (tmpPSFilePath != null) Files.deleteIfExists(tmpPSFilePath);
            if (tmpJPEGFilePath != null) Files.deleteIfExists(tmpJPEGFilePath);
        }
        return imageBytes;
    }

    /**
     * Get the first page of a PDF as a JPEG image
     *
     * @param is the input stream
     * @return the JPEG image as a byte array
     * @throws IOException if an error occurs during the conversion
     */
    private byte[] getFromPDF(InputStream is) throws IOException {
        byte[] imageBytes = null;
        try (PDDocument document = Loader.loadPDF(is.readAllBytes())) {
            PDFRenderer pdfRenderer = new PDFRenderer(document);
            if (document.getNumberOfPages()>0) {
                // Render the page as an image at 300 DPI
                BufferedImage bufferedImage = pdfRenderer.renderImageWithDPI(0, 300);
                ByteArrayOutputStream jpegPreviewBAOS = new ByteArrayOutputStream();
                ImageIO.write(bufferedImage, "JPEG", jpegPreviewBAOS);
                imageBytes = jpegPreviewBAOS.toByteArray();
            }
        }
        return imageBytes;
    }
}
