package io.sailrocket.core.client;

import io.netty.buffer.ByteBuf;
import io.sailrocket.api.HttpClient;

public class RequestContext {

    //TODO:: just for POC
    public HttpClient client;
    public final String path;
    public final ByteBuf payload;

    public RequestContext(HttpClientBuilder clientBuilder, String path) {
        this(clientBuilder  , path, null);
    }

    public RequestContext(HttpClientBuilder clientBuilder, String path, ByteBuf payload) {
        this.path = path;
        this.payload = payload;
        try {
            this.client = clientBuilder.build();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
