package com.reliableautomoto.attendance;

import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class QrDecoder {
    private final MultiFormatReader reader = new MultiFormatReader();

    public QrDecoder() {
        reader.setHints(Map.of(DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER, Boolean.TRUE, DecodeHintType.CHARACTER_SET, "UTF-8"));
    }

    public Optional<String> decode(BufferedImage image) {
        if (image == null) return Optional.empty();
        try {
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
            return Optional.of(reader.decodeWithState(bitmap).getText());
        } catch (NotFoundException ignored) {
            return Optional.empty();
        } finally {
            reader.reset();
        }
    }
}
