Tests should all allow for:
 - config (dev|qa|...)
 - tenantId (null means create new tenant)
 - preserveData (don't delete on complete; default false)
 - resetTenant (delete on init; default false. Only used when tenantId provided)

In memory tests use conventional boot / DI. Don't fight the system; configure it

External tests (REST/etc) wait for process to be ready
```
  @QuarkusTest
  public class StartupTest {

      @Test
      public void testStartupCompletes() {
          // If test runs, startup succeeded (wasn't blocked)
          assertTrue(true);
      }

      @Test
      public void testServiceReady() {
          // Check readiness
          given()
              .when().get("/q/health/ready")
              .then()
              .statusCode(200)
              .body("status", is("UP"));
      }
  }
```

Tests use SLF4J logger (no System.out.println)
