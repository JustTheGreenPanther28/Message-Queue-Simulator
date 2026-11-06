package com.tilechat.common.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImageTypesTest {
    @Test
    void detectsImageTypesByMagicBytes() {
        assertThat(ImageTypes.detectExtension(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0})).isEqualTo("png");
        assertThat(ImageTypes.detectExtension(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})).isEqualTo("jpg");
        assertThat(ImageTypes.detectExtension("GIF89a".getBytes())).isEqualTo("gif");
        assertThat(ImageTypes.detectExtension(new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'})).isEqualTo("webp");
        assertThat(ImageTypes.detectExtension("<html>hello".getBytes())).isNull();
    }
}
