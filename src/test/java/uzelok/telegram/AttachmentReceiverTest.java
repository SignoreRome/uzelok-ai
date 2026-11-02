package uzelok.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uzelok.config.UzelokProperties;
import uzelok.domain.exception.AttachmentTooLarge;
import uzelok.domain.model.SourceItem;
import uzelok.domain.model.SourceKind;
import uzelok.support.InMemoryAttachmentRepository;
import uzelok.support.InMemoryTelegramGateway;
import uzelok.support.TestImages;

class AttachmentReceiverTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID KNOWLEDGE_BASE_ID = UUID.randomUUID();
    private static final Instant CREATED_AT = Instant.parse("2026-01-15T10:00:00Z");

    @TempDir
    Path artifacts;

    private InMemoryAttachmentRepository repository;
    private InMemoryTelegramGateway gateway;
    private UzelokProperties.Telegram props;
    private AttachmentReceiver receiver;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAttachmentRepository();
        gateway = new InMemoryTelegramGateway();
        props = new UzelokProperties.Telegram(
                "token", "test_bot", 30, 2000, 8000, 15_728_640L, 1_048_576L, 30_000_000L);
        receiver = new AttachmentReceiver(
                props, new UzelokProperties.Storage(artifacts.toString()), repository, gateway,
                new Messages());
    }

    @Test
    void jpegPngAndWebpAreAcceptedByMagicBytes() {
        assertImage("jpeg", TestImages.jpeg(640, 480), "image/jpeg", "jpg");
        assertImage("png", TestImages.png(800, 600), "image/png", "png");
        assertImage("webp", TestImages.webp(1024, 768), "image/webp", "webp");
    }

    @Test
    void pdfWithJpgExtensionIsRejected() {
        byte[] pdf = TestImages.pdf("not an image");
        gateway.putFile("pdf", pdf);

        assertThatThrownBy(() -> receiver.receive(
                photoItem(), "pdf", "image/jpeg", pdf.length, "scan.jpg"))
                .isInstanceOf(AttachmentRejected.class)
                .hasMessageContaining("формат");
    }

    @Test
    void declaredOversizeIsRejectedBeforeDownload() {
        long declaredSize = props.maxPhotoBytes() + 1;

        assertThatThrownBy(() -> receiver.receive(
                photoItem(), "photo", null, declaredSize, null))
                .isInstanceOf(AttachmentRejected.class)
                .hasMessage(new Messages().text("photo.too_big",
                        String.valueOf(declaredSize), String.valueOf(props.maxPhotoBytes())));

        assertThat(gateway.downloadCalls()).isEmpty();
    }

    @Test
    void declaredOversizeDocumentIsRejectedBeforeDownload() {
        long declaredSize = props.maxDocumentBytes() + 1;
        SourceItem item = documentItem();

        assertThatThrownBy(() -> receiver.receive(
                item, "doc", "text/plain", declaredSize, "note.txt"))
                .isInstanceOf(AttachmentRejected.class)
                .hasMessage(new Messages().text("document.too_big",
                        String.valueOf(declaredSize), String.valueOf(props.maxDocumentBytes())));

        assertThat(gateway.downloadCalls()).isEmpty();
        assertThat(repository.countBySourceItem(item.id())).isZero();
    }

    @Test
    void downloadOverLimitIsRejectedByGateway() {
        UzelokProperties.Telegram small = new UzelokProperties.Telegram(
                "token", "test_bot", 30, 2000, 8000, 10L, 10L, 30_000_000L);
        AttachmentReceiver smallReceiver = new AttachmentReceiver(
                small, new UzelokProperties.Storage(artifacts.toString()), repository, gateway,
                new Messages());
        byte[] image = TestImages.jpeg(10, 10);
        gateway.putFile("big", image);

        assertThatThrownBy(() -> smallReceiver.receive(
                photoItem(), "big", null, 0, null))
                .isInstanceOf(AttachmentTooLarge.class);
    }

    @Test
    void nonUtf8DocumentIsRejectedWithLocalizedText() {
        byte[] invalid = {(byte) 0xC3, 0x28, 0x00};
        gateway.putFile("bad-text", invalid);

        assertThatThrownBy(() -> receiver.receive(
                documentItem(), "bad-text", "text/plain", 0, "note.txt"))
                .isInstanceOf(AttachmentRejected.class)
                .hasMessage(new Messages().text("attachment.bad_text"));
    }

    @Test
    void imageWithTooManyPixelsIsRejected() {
        byte[] image = TestImages.jpeg(8000, 5000);
        gateway.putFile("huge", image);

        assertThatThrownBy(() -> receiver.receive(
                photoItem(), "huge", null, image.length, null))
                .isInstanceOf(AttachmentRejected.class)
                .hasMessageContaining("пикселей");
    }

    @Test
    void storedFileHasExpectedSha256AndPath() throws Exception {
        byte[] image = TestImages.png(320, 240);
        gateway.putFile("png", image);

        AttachmentReceiver.StoredAttachment stored = receiver.receive(
                photoItem(), "png", null, image.length, null);

        String expectedSha = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(image));
        assertThat(stored.sha256()).isEqualTo(expectedSha);
        assertThat(stored.byteSize()).isEqualTo(image.length);
        assertThat(stored.mime()).isEqualTo("image/png");
        assertThat(stored.path()).isEqualTo(artifacts.resolve(TENANT_ID.toString())
                .resolve("2026")
                .resolve("01")
                .resolve(expectedSha + ".png"));
        assertThat(Files.exists(stored.path())).isTrue();
    }

    @Test
    void repeatedWriteOfSameFileDoesNotFail() {
        byte[] image = TestImages.webp(40, 30);
        gateway.putFile("webp", image);
        SourceItem item = photoItem();

        receiver.receive(item, "webp", null, image.length, null);
        receiver.receive(item, "webp", null, image.length, null);

        assertThat(repository.countBySourceItem(item.id())).isEqualTo(2);
        assertThat(gateway.downloadCalls()).hasSize(2);
    }

    private void assertImage(String fileId, byte[] bytes, String expectedMime, String extension) {
        gateway.putFile(fileId, bytes);
        SourceItem item = photoItem();

        AttachmentReceiver.StoredAttachment stored = receiver.receive(
                item, fileId, null, bytes.length, null);

        assertThat(stored.mime()).isEqualTo(expectedMime);
        assertThat(stored.path().getFileName().toString()).endsWith("." + extension);
        assertThat(Files.exists(stored.path())).isTrue();
    }

    private static SourceItem photoItem() {
        return new SourceItem(
                UUID.randomUUID(),
                TENANT_ID,
                KNOWLEDGE_BASE_ID,
                100L,
                500L,
                SourceKind.PHOTO,
                null,
                null,
                CREATED_AT);
    }

    private static SourceItem documentItem() {
        return new SourceItem(
                UUID.randomUUID(),
                TENANT_ID,
                KNOWLEDGE_BASE_ID,
                100L,
                501L,
                SourceKind.DOCUMENT,
                null,
                null,
                CREATED_AT);
    }
}
