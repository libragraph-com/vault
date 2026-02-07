package com.libragraph.vault.formats.codecs;

import com.libragraph.vault.formats.api.Codec;
import com.libragraph.vault.util.buffer.BinaryData;
import com.libragraph.vault.util.buffer.Buffer;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Codec for GZIP compression (.gz files).
 * Handles both single .gz files and .tar.gz archives.
 */
@ApplicationScoped
public class GzipCodec implements Codec {
    private static final byte[] GZIP_MAGIC = new byte[]{0x1f, (byte) 0x8b};

    @Override
    public boolean matches(byte[] header, String filename) {
        if (header.length >= 2) {
            if (header[0] == GZIP_MAGIC[0] && header[1] == GZIP_MAGIC[1]) {
                return true;
            }
        }

        if (filename != null) {
            String lower = filename.toLowerCase();
            return lower.endsWith(".gz") || lower.endsWith(".gzip");
        }

        return false;
    }

    @Override
    public BinaryData decode(BinaryData input) {
        try {
            Buffer output = Buffer.allocate(Math.max(input.size() * 3, 4096));
            try (GZIPInputStream gzip = new GZIPInputStream(input.inputStream(0));
                 OutputStream out = output.outputStream(0)) {
                gzip.transferTo(out);
            }
            return output;
        } catch (Exception e) {
            throw new RuntimeException("Failed to decompress GZIP", e);
        }
    }

    @Override
    public BinaryData encode(BinaryData input, Map<String, Object> parameters) {
        try {
            Buffer output = Buffer.allocate(Math.max(input.size() / 3, 4096));
            try (InputStream in = input.inputStream(0);
                 GZIPOutputStream gzip = new GZIPOutputStream(output.outputStream(0))) {
                in.transferTo(gzip);
            }
            return output;
        } catch (Exception e) {
            throw new RuntimeException("Failed to compress with GZIP", e);
        }
    }

    @Override
    public Map<String, Object> getEncodingParameters() {
        return new HashMap<>();
    }
}
