package com.bifos.assistant.chat.application;

import com.bifos.assistant.chat.domain.ChatAttachment;
import com.bifos.assistant.chat.infra.AttachmentStore;
import com.bifos.assistant.shared.error.ApiException;
import com.bifos.assistant.shared.error.ErrorCode;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 원본 해상도를 유지하고 표시 좌표 영역만 먼저 디코딩한다. 사본 처리와 독립된 한 장의 decode 자리를 쓴다. */
@Component
@RequiredArgsConstructor
public class AttachmentInspection {
    public static final int MAX_OUTPUT_BYTES = 10 * 1024 * 1024;
    private static final int MAX_SOURCE_BYTES = 20 * 1024 * 1024;
    private static final long MAX_RESULT_PIXELS = 16_000_000;
    private final AttachmentStore store;
    private final Semaphore decoding = new Semaphore(1, true);

    /** 매 turn에 복원할 표시 크기다. 머리만 읽고 디코딩하지 않는다. */
    public String displaySize(ChatAttachment attachment) {
        if (!attachment.isVisible()) {
            return "보관 종료";
        }
        try (InputStream in = store.open(attachment)) {
            byte[] header = in.readNBytes(256 * 1024);
            return AgentImageResizer.dimensions(header)
                    .map(size -> AgentImageResizer.orientation(header) >= 5
                            ? size.height + "x" + size.width
                            : size.width + "x" + size.height)
                    .orElse("확인 불가");
        } catch (IOException | RuntimeException ex) {
            return "확인 불가";
        }
    }

    public InspectedImage read(ChatAttachment attachment, List<Integer> region) {
        return read(attachment, region, () -> true);
    }

    public InspectedImage read(ChatAttachment attachment, List<Integer> region, BooleanSupplier active) {
        if (!List.of("image/jpeg", "image/png").contains(attachment.contentType())) {
            throw invalid("original format is unsupported");
        }
        acquire(active);
        try (InputStream in = store.open(attachment)) {
            byte[] original = in.readNBytes(MAX_SOURCE_BYTES + 1);
            if (original.length > MAX_SOURCE_BYTES) {
                throw invalid("original exceeds safe byte limit");
            }
            return inspect(original, attachment.contentType(), region);
        } catch (IOException ex) {
            throw invalid("could not read original image");
        } finally {
            decoding.release();
        }
    }

    private void acquire(BooleanSupplier active) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        try {
            while (System.nanoTime() < deadline) {
                if (!active.getAsBoolean()) {
                    throw new ApiException(ErrorCode.MCP_CALL_CONTEXT_INVALID, "execution is no longer active");
                }
                if (decoding.tryAcquire(500, TimeUnit.MILLISECONDS)) {
                    try {
                        if (!active.getAsBoolean()) {
                            throw new ApiException(ErrorCode.MCP_CALL_CONTEXT_INVALID, "execution is no longer active");
                        }
                        return;
                    } catch (RuntimeException ex) {
                        decoding.release();
                        throw ex;
                    }
                }
            }
            throw invalid("original decoding wait timed out");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw invalid("original decoding was interrupted");
        }
    }

    static InspectedImage inspect(byte[] original, String mime, List<Integer> region) throws IOException {
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(original))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalid("original image is corrupt");
            }
            ImageReader reader = readers.next();
            try {
                if (!("JPEG".equalsIgnoreCase(reader.getFormatName()) && "image/jpeg".equals(mime))
                        && !("PNG".equalsIgnoreCase(reader.getFormatName()) && "image/png".equals(mime))) {
                    throw invalid("original image format does not match its stored type");
                }
                boolean[] warning = {false};
                reader.addIIOReadWarningListener((source, message) -> warning[0] = true);
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (!AgentImageResizer.withinPixelLimit(width, height, AgentImageResizer.MAX_PIXELS)) {
                    throw invalid("original exceeds safe pixel limit");
                }
                int orientation = AgentImageResizer.orientation(original);
                Rectangle source = sourceRegion(width, height, orientation, region);
                if (!AgentImageResizer.withinPixelLimit(source.width, source.height, MAX_RESULT_PIXELS)) {
                    throw limit();
                }
                ImageReadParam parameters = reader.getDefaultReadParam();
                parameters.setSourceRegion(source);
                BufferedImage decoded = reader.read(0, parameters);
                if (decoded == null || warning[0]) {
                    throw invalid("original image is corrupt");
                }
                if (region == null && orientation == 1) {
                    if (original.length > MAX_OUTPUT_BYTES) {
                        throw limit();
                    }
                    return new InspectedImage(mime, original);
                }
                BufferedImage display = AgentImageResizer.draw(decoded, orientation, Integer.MAX_VALUE);
                ByteArrayOutputStream output = new ByteArrayOutputStream() {
                    @Override
                    public synchronized void write(byte[] bytes, int offset, int length) {
                        if ((long) count + length > MAX_OUTPUT_BYTES) {
                            throw limit();
                        }
                        super.write(bytes, offset, length);
                    }
                };
                if (!ImageIO.write(display, "png", output)) {
                    throw invalid("could not encode original region");
                }
                return new InspectedImage("image/png", output.toByteArray());
            } catch (IOException ex) {
                Throwable cause = ex;
                while (cause != null) {
                    if (cause instanceof ApiException api) {
                        throw api;
                    }
                    cause = cause.getCause();
                }
                throw invalid("original image is corrupt or region cannot be encoded");
            } finally {
                reader.dispose();
            }
        }
    }

    private static Rectangle sourceRegion(int width, int height, int orientation, List<Integer> region) {
        Dimension display = orientation >= 5 ? new Dimension(height, width) : new Dimension(width, height);
        if (region == null) {
            return new Rectangle(0, 0, width, height);
        }
        if (region.size() != 4
                || region.stream().anyMatch(n -> n == null || n < 0)
                || region.get(2) <= region.get(0)
                || region.get(3) <= region.get(1)
                || region.get(2) > display.width
                || region.get(3) > display.height) {
            throw invalid("region is outside original display coordinates");
        }
        try {
            var inverse = AgentImageResizer.orientationTransform(orientation, width, height)
                    .createInverse();
            Point2D first = inverse.transform(new Point2D.Double(region.get(0), region.get(1)), null);
            Point2D last = inverse.transform(new Point2D.Double(region.get(2), region.get(3)), null);
            return new Rectangle(
                    (int) Math.min(first.getX(), last.getX()),
                    (int) Math.min(first.getY(), last.getY()),
                    (int) Math.abs(first.getX() - last.getX()),
                    (int) Math.abs(first.getY() - last.getY()));
        } catch (NoninvertibleTransformException ex) {
            throw invalid("invalid image orientation");
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.ATTACHMENT_INSPECTION_FAILED, message);
    }

    private static ApiException limit() {
        return new ApiException(
                ErrorCode.ATTACHMENT_INSPECTION_LIMIT, "select a smaller region in original display coordinates");
    }
}
