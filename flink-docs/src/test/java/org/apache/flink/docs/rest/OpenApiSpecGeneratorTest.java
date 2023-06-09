/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.docs.rest;

import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.docs.rest.data.TestAdditionalFieldsMessageHeaders;
import org.apache.flink.docs.rest.data.TestEmptyMessageHeaders;
import org.apache.flink.docs.rest.data.TestExcludeMessageHeaders;
import org.apache.flink.runtime.rest.handler.RestHandlerSpecification;
import org.apache.flink.runtime.rest.util.DocumentingRestEndpoint;
import org.apache.flink.runtime.rest.versioning.RuntimeRestAPIVersion;

import org.apache.flink.shaded.netty4.io.netty.channel.ChannelInboundHandler;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.StringSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Test class for {@link OpenApiSpecGenerator}. */
class OpenApiSpecGeneratorTest {

    @Test
    void testTitle(@TempDir Path tmpDir) throws Exception {
        final String title = "Funky title";

        final Path file = tmpDir.resolve("openapi_spec.yaml");
        OpenApiSpecGenerator.createDocumentationFile(
                title, new TestExcludeDocumentingRestEndpoint(), RuntimeRestAPIVersion.V0, file);
        final String actual = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);

        assertThat(actual).contains("title: " + title);
    }

    @Test
    void testExcludeFromDocumentation(@TempDir Path tmpDir) throws Exception {
        final Path file = tmpDir.resolve("openapi_spec.yaml");
        OpenApiSpecGenerator.createDocumentationFile(
                "title", new TestExcludeDocumentingRestEndpoint(), RuntimeRestAPIVersion.V0, file);
        final String actual = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);

        assertThat(actual).contains("/test/empty1");
        assertThat(actual).contains("This is a testing REST API.");
        assertThat(actual).contains("/test/empty2");
        assertThat(actual).contains("This is another testing REST API.");
        assertThat(actual).doesNotContain("/test/exclude1");
        assertThat(actual)
                .doesNotContain("This REST API should not appear in the generated documentation.");
        assertThat(actual).doesNotContain("/test/exclude2");
        assertThat(actual)
                .doesNotContain(
                        "This REST API should also not appear in the generated documentation.");
    }

    private static class TestExcludeDocumentingRestEndpoint implements DocumentingRestEndpoint {

        @Override
        public List<Tuple2<RestHandlerSpecification, ChannelInboundHandler>> initializeHandlers(
                CompletableFuture<String> localAddressFuture) {
            return Arrays.asList(
                    Tuple2.of(
                            new TestEmptyMessageHeaders(
                                    "/test/empty1", "This is a testing REST API."),
                            null),
                    Tuple2.of(
                            new TestEmptyMessageHeaders(
                                    "/test/empty2", "This is another testing REST API."),
                            null),
                    Tuple2.of(
                            new TestExcludeMessageHeaders(
                                    "/test/exclude1",
                                    "This REST API should not appear in the generated documentation."),
                            null),
                    Tuple2.of(
                            new TestExcludeMessageHeaders(
                                    "/test/exclude2",
                                    "This REST API should also not appear in the generated documentation."),
                            null));
        }
    }

    @Test
    void testDuplicateOperationIdsAreRejected(@TempDir Path tmpDir) {
        final Path file = tmpDir.resolve("openapi_spec.yaml");
        assertThatThrownBy(
                        () ->
                                OpenApiSpecGenerator.createDocumentationFile(
                                        "title",
                                        new TestDuplicateOperationIdDocumentingRestEndpoint(),
                                        RuntimeRestAPIVersion.V0,
                                        file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate OperationId");
    }

    private static class TestDuplicateOperationIdDocumentingRestEndpoint
            implements DocumentingRestEndpoint {

        @Override
        public List<Tuple2<RestHandlerSpecification, ChannelInboundHandler>> initializeHandlers(
                CompletableFuture<String> localAddressFuture) {
            return Arrays.asList(
                    Tuple2.of(new TestEmptyMessageHeaders("operation1"), null),
                    Tuple2.of(new TestEmptyMessageHeaders("operation1"), null));
        }
    }

    @Test
    void testAdditionalFields(@TempDir Path tmpDir) throws Exception {
        final OpenAPI documentation =
                OpenApiSpecGenerator.createDocumentation(
                        "title", new TestAdditionalFieldsRestEndpoint(), RuntimeRestAPIVersion.V0);
        assertThat(documentation.getComponents().getSchemas())
                .extractingByKey("AdditionalFieldsRequestBody")
                .satisfies(
                        x ->
                                assertThat(x.getAdditionalProperties())
                                        .isInstanceOf(StringSchema.class));
    }

    private static class TestAdditionalFieldsRestEndpoint implements DocumentingRestEndpoint {

        @Override
        public List<Tuple2<RestHandlerSpecification, ChannelInboundHandler>> initializeHandlers(
                CompletableFuture<String> localAddressFuture) {
            return Collections.singletonList(
                    Tuple2.of(new TestAdditionalFieldsMessageHeaders("operation1"), null));
        }
    }
}
