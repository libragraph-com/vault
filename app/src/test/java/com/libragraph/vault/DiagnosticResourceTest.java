package com.libragraph.vault;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class DiagnosticResourceTest {

    @Test
    void ping_returnsOk() {
        given()
                .when().get("/api/diagnostic/ping")
                .then()
                .statusCode(200)
                .body("status", is("ok"))
                .body("message", is("Vault is running"));
    }

    @Test
    void info_returnsAppMetadata() {
        given()
                .when().get("/api/diagnostic/info")
                .then()
                .statusCode(200)
                .body("name", is("vault"))
                .body("version", notNullValue())
                .body("java", notNullValue())
                .body("profile", notNullValue());
    }

    @Test
    void healthReady_returnsUp() {
        given()
                .when().get("/q/health/ready")
                .then()
                .statusCode(200)
                .body("status", is("UP"));
    }

    @Test
    void healthLive_returnsUp() {
        given()
                .when().get("/q/health/live")
                .then()
                .statusCode(200)
                .body("status", is("UP"));
    }
}
