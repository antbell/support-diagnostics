/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package co.elastic.support.diagnostics;

import co.elastic.support.Constants;
import co.elastic.support.util.JsonYamlUtils;
import co.elastic.support.util.ResourceCache;
import com.google.common.io.Files;
import org.junit.jupiter.api.*;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestDiagnosticService {
    private ClientAndServer mockServer;

    static private String headerKey1 = "k1";
    static private String headerVal1 = "v1";
    static private String headerKey2 = "k2";
    static private String headerVal2 = "v2";

    @BeforeAll
    public void globalSetup() {
        mockServer = startClientAndServer(9880);
        // mockserver by default is in verbose mode (useful when creating new test), move it to warning.
        ConfigurationProperties.disableSystemOut(true);
        ConfigurationProperties.logLevel("WARN");
        ResourceCache.terminal.dispose();
    }

    private DiagConfig newDiagConfig() {
        Map diagMap = Collections.emptyMap();
        try {
            diagMap = JsonYamlUtils.readYamlFromClasspath(Constants.DIAG_CONFIG, true);
        } catch (DiagnosticException e) {
            fail(e);
        }
        return new DiagConfig(diagMap);
    }

    private DiagnosticInputs newDiagnosticInputs() {
        DiagnosticInputs diagnosticInputs = new DiagnosticInputs();
        diagnosticInputs.port = 9880;
        diagnosticInputs.outputDir = Files.createTempDir().toString();
        return diagnosticInputs;
    }

    private HttpRequest myRequest(Boolean withHeaders) {
        if (withHeaders) {
            return request().withHeaders(
                    new Header(headerKey1, headerVal1),
                    new Header(headerKey2, headerVal2)
            );
        } else {
            return request();
        }
    }

    private void setupResponse(Boolean withHeaders) {
        mockServer
                .when(
                        myRequest(withHeaders)
                                .withPath("/")
                )
                .respond(
                        response()
                                .withBody("{\"version\": {\"number\": \"7.14.0\"}}")
                );
        mockServer
                .when(
                        myRequest(withHeaders)
                )
                .respond(
                        response()
                                .withBody("some_response_body")
                );
    }

    @AfterAll
    public void globalTeardown() {
        mockServer.stop();
    }

    @Test
    public void testWithExtraHeaders() {
        setupResponse(true);

        Map extraHeaders = new HashMap<String, String>();
        extraHeaders.put(headerKey1, headerVal1);
        extraHeaders.put(headerKey2, headerVal2);
        DiagConfig diagConfig = newDiagConfig();
        diagConfig.extraHeaders = extraHeaders;
        DiagnosticService diag = new DiagnosticService();

        try {
            File result = diag.exec(newDiagnosticInputs(), diagConfig);
            assertTrue(result.toString().matches(".*\\.zip$"), result.toString());
            try {
                ZipFile zipFile = new ZipFile(result, ZipFile.OPEN_READ);
                Set<String> filenames = new HashSet<>();

                Enumeration<? extends ZipEntry> entries = zipFile.entries();

                while(entries.hasMoreElements()){
                    ZipEntry entry = entries.nextElement();
                    // Add file path without leading directory
                    filenames.add(entry.getName().replaceFirst("/[^/]*/", ""));
                }
                assertTrue(filenames.contains("manifest.json"));
            } catch (IOException e) {
                fail("Error processing result zip file", e);
            }
        } catch (DiagnosticException e) {
            fail(e);
        }
    }

    @Test
    public void testWithoutExtraHeaders() {
        setupResponse(false);

        DiagnosticService diag = new DiagnosticService();

        try {
            File result = diag.exec(newDiagnosticInputs(), newDiagConfig());
        } catch (DiagnosticException e) {
            fail(e);
        }
    }
}
