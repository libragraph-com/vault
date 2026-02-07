Code API:
Classes in core should use import io.quarkus.logging.Log; 
Tests and shared code/libraries should use SLF4J

Back-End:
Both impls should be handled by JBoss logging
Log config per profile
Log config for test runner
