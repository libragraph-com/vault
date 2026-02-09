package com.libragraph.vault.test;

import com.libragraph.vault.formats.api.*;
import com.libragraph.vault.formats.handlers.ZipHandlerFactory;
import com.libragraph.vault.util.buffer.BinaryData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Test-only TIER_2 format handler that matches {@code .tier2} extension.
 * Delegates child extraction to the real ZIP handler but reports TIER_2 capabilities,
 * simulating a format (like 7Z or RAR) whose original must be stored alongside
 * bonus decomposition.
 */
@ApplicationScoped
public class Tier2TestHandlerFactory implements FormatHandlerFactory {

    @Inject
    ZipHandlerFactory zipFactory;

    @Override
    public DetectionCriteria getDetectionCriteria() {
        return new DetectionCriteria(
                Set.of("application/x-tier2-test"),
                Set.of("tier2"),
                null, 0, 300 // higher priority than ZIP (200)
        );
    }

    @Override
    public Handler createInstance(BinaryData buffer, FileContext context) {
        // Create a real ZIP handler for extraction, wrap with TIER_2 capabilities
        Handler zipHandler = zipFactory.createInstance(buffer, context);
        return new Tier2TestHandler(zipHandler);
    }

    private static class Tier2TestHandler implements Handler {
        private final Handler delegate;

        Tier2TestHandler(Handler delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean hasChildren() {
            return delegate.hasChildren();
        }

        @Override
        public boolean isCompressible() {
            return delegate.isCompressible();
        }

        @Override
        public ContainerCapabilities getCapabilities() {
            return ContainerCapabilities.tier2();
        }

        @Override
        public Stream<ContainerChild> extractChildren() {
            return delegate.extractChildren();
        }

        @Override
        public void reconstruct(List<ContainerChild> children, OutputStream output) throws IOException {
            throw new UnsupportedOperationException("TIER_2 formats do not support reconstruction from children");
        }

        @Override
        public Map<String, Object> extractMetadata() {
            return delegate.extractMetadata();
        }

        @Override
        public String extractText() {
            return delegate.extractText();
        }

        @Override
        public void close() throws Exception {
            delegate.close();
        }
    }
}
