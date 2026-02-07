package com.libragraph.vault.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;

@Path("/api/diagnostic")
@Produces(MediaType.APPLICATION_JSON)
public class DiagnosticResource {

    @ConfigProperty(name = "quarkus.application.name")
    String appName;

    @ConfigProperty(name = "quarkus.application.version")
    String appVersion;

    @ConfigProperty(name = "quarkus.profile", defaultValue = "prod")
    String profile;

    @GET
    @Path("/ping")
    public Map<String, String> ping() {
        return Map.of(
                "status", "ok",
                "message", "Vault is running"
        );
    }

    @GET
    @Path("/info")
    public Map<String, String> info() {
        return Map.of(
                "name", appName,
                "version", appVersion,
                "java", System.getProperty("java.version"),
                "profile", profile
        );
    }
}
