package com.libragraph.vault.formats.codecs;

import com.libragraph.vault.formats.api.Codec;
import com.libragraph.vault.util.buffer.BinaryData;
import com.libragraph.vault.util.buffer.Buffer;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Codec for BZIP2 compression (.bz2 files).
 * Uses Apache Commons Compress for BZIP2 support.
 */
@ApplicationScoped
public class Bzip2Codec implements Codec {
    private static final byte[] BZIP2_MAGIC = new byte[]{'B', 'Z', 'h'};

    @Override
    public boolean matches(byte[] header, String filename) {
        if (header.length >= 3) {
            if (header[0] == BZIP2_MAGIC[0] &&
                header[1] == BZIP2_MAGIC[1] &&
                header[2] == BZIP2_MAGIC[2]) {
                return true;
            }
        }

        if (filename != null) {
            String lower = filename.toLowerCase();
            return lower.endsWith(".bz2") || lower.endsWith(".bzip2");
        }

        return false;
    }

    @Override
    public BinaryData decode(BinaryData input) {
        try {
            Buffer output = Buffer.allocate(Math.max(input.size() * 3, 4096));
            try (BZip2CompressorInputStream bzip2 = new BZip2CompressorInputStream(input.inputStream(0));
                 OutputStream out = output.outputStream(0)) {
                bzip2.transferTo(out);
            }
            return output;
        } catch (Exception e) {
            throw new RuntimeException("Failed to decompress BZIP2", e);
        }
    }

    @Override
    public BinaryData encode(BinaryData input, Map<String, Object> parameters) {
        try {
            Buffer output = Buffer.allocate(Math.max(input.size() / 3, 4096));

            int blockSize = 9;
            if (parameters.containsKey("blockSize")) {
                blockSize = (Integer) parameters.get("blockSize");
                if (blockSize < 1 || blockSize > 9) {
                    throw new IllegalArgumentException("BZIP2 block size must be 1-9, got: " + blockSize);
                }
            }

            try (InputStream in = input.inputStream(0);
                 BZip2CompressorOutputStream bzip2 = new BZip2CompressorOutputStream(
                     output.outputStream(0), blockSize)) {
                in.transferTo(bzip2);
            }
            return output;
        } catch (Exception e) {
            throw new RuntimeException("Failed to compress with BZIP2", e);
        }
    }

    @Override
    public Map<String, Object> getEncodingParameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("blockSize", 9);
        return params;
    }
}
