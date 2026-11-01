package uzelok.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import uzelok.support.TestImages;

class ImageHeaderTest {

    @Test
    void readsJpegSof0() {
        assertThat(ImageHeader.read(TestImages.jpeg(640, 480), "image/jpeg"))
                .contains(new ImageSize(640, 480));
    }

    @Test
    void readsPngIhdr() {
        assertThat(ImageHeader.read(TestImages.png(800, 600), "image/png"))
                .contains(new ImageSize(800, 600));
    }

    @Test
    void readsWebpVp8x() {
        assertThat(ImageHeader.read(TestImages.webp(1024, 768), "image/webp"))
                .contains(new ImageSize(1024, 768));
    }

    @Test
    void garbageHasNoDimensions() {
        assertThat(ImageHeader.read(new byte[] {1, 2, 3, 4, 5, 6}, "image/jpeg"))
                .isEmpty();
        assertThat(ImageHeader.read(new byte[] {1, 2, 3, 4, 5, 6}, "image/png"))
                .isEmpty();
        assertThat(ImageHeader.read(new byte[] {1, 2, 3, 4, 5, 6}, "image/webp"))
                .isEmpty();
    }
}
