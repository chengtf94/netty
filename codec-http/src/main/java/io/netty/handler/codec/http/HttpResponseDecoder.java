/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.http;

/**
 * HTTP详情解码器
 */
public class HttpResponseDecoder extends HttpObjectDecoder {

    private static final HttpResponseStatus UNKNOWN_STATUS = new HttpResponseStatus(999, "Unknown");

    /**
     * Creates a new instance with the default
     * {@code maxInitialLineLength (4096)}, {@code maxHeaderSize (8192)}, and
     * {@code maxChunkSize (8192)}.
     */
    public HttpResponseDecoder() {
    }

    /**
     * Creates a new instance with the specified parameters.
     */
    public HttpResponseDecoder(
            int maxInitialLineLength, int maxHeaderSize, int maxChunkSize) {
        super(maxInitialLineLength, maxHeaderSize, maxChunkSize, DEFAULT_CHUNKED_SUPPORTED);
    }

    public HttpResponseDecoder(
            int maxInitialLineLength, int maxHeaderSize, int maxChunkSize, boolean validateHeaders) {
        super(maxInitialLineLength, maxHeaderSize, maxChunkSize, DEFAULT_CHUNKED_SUPPORTED, validateHeaders);
    }

    public HttpResponseDecoder(
            int maxInitialLineLength, int maxHeaderSize, int maxChunkSize, boolean validateHeaders,
            int initialBufferSize) {
        super(maxInitialLineLength, maxHeaderSize, maxChunkSize, DEFAULT_CHUNKED_SUPPORTED, validateHeaders,
              initialBufferSize);
    }

    public HttpResponseDecoder(
            int maxInitialLineLength, int maxHeaderSize, int maxChunkSize, boolean validateHeaders,
            int initialBufferSize, boolean allowDuplicateContentLengths) {
        super(maxInitialLineLength, maxHeaderSize, maxChunkSize, DEFAULT_CHUNKED_SUPPORTED, validateHeaders,
              initialBufferSize, allowDuplicateContentLengths);
    }

    @Override
    protected HttpMessage createMessage(String[] initialLine) {
        return new DefaultHttpResponse(
                HttpVersion.valueOf(initialLine[0]),
                HttpResponseStatus.valueOf(Integer.parseInt(initialLine[1]), initialLine[2]), validateHeaders);
    }

    @Override
    protected HttpMessage createInvalidMessage() {
        return new DefaultFullHttpResponse(HttpVersion.HTTP_1_0, UNKNOWN_STATUS, validateHeaders);
    }

    @Override
    protected boolean isDecodingRequest() {
        return false;
    }
}
