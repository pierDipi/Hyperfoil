package io.sailrocket.core.steps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import io.netty.buffer.ByteBuf;
import io.sailrocket.api.http.HttpMethod;
import io.sailrocket.api.http.HttpRequest;
import io.sailrocket.api.collection.RequestQueue;
import io.sailrocket.api.config.Step;
import io.sailrocket.api.session.Session;
import io.sailrocket.core.builders.BaseSequenceBuilder;
import io.sailrocket.core.builders.BaseStepBuilder;
import io.sailrocket.core.api.ResourceUtilizer;
import io.sailrocket.core.util.Util;
import io.sailrocket.function.SerializableBiConsumer;
import io.sailrocket.function.SerializableFunction;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

public class HttpRequestStep implements Step, ResourceUtilizer {
   private static final Logger log = LoggerFactory.getLogger(HttpRequestStep.class);
   private static final boolean trace = log.isTraceEnabled();

   private final HttpMethod method;
   private final SerializableFunction<Session, String> pathGenerator;
   private final SerializableFunction<Session, ByteBuf> bodyGenerator;
   private final SerializableBiConsumer<Session, HttpRequest>[] headerAppenders;
   private final long timeout;
   private final HttpResponseHandler handler;

   public HttpRequestStep(HttpMethod method,
                          SerializableFunction<Session, String> pathGenerator,
                          SerializableFunction<Session, ByteBuf> bodyGenerator,
                          SerializableBiConsumer<Session, HttpRequest>[] headerAppenders,
                          long timeout, HttpResponseHandler handler) {
      this.method = method;
      this.pathGenerator = pathGenerator;
      this.bodyGenerator = bodyGenerator;
      this.headerAppenders = headerAppenders;
      this.timeout = timeout;
      this.handler = handler;
   }

   @Override
   public boolean invoke(Session session) {
      HttpResponseHandler.HandlerInstances h = session.getResource(handler);

      if (session.requestQueue().isDepleted()) {
         log.warn("Request queue too small; increase it to prevent blocking.");
         return false;
      }

      ByteBuf body = bodyGenerator == null ? null : bodyGenerator.apply(session);
      String path = pathGenerator.apply(session);

      // TODO alloc!
      HttpRequest httpRequest = session.httpConnectionPool().request(method, path, body);
      if (httpRequest == null) {
         // TODO: we should probably record being blocked due to insufficient connections
         log.warn("No HTTP connection in pool, waiting...");
         session.httpConnectionPool().registerWaitingSession(session);
         return false;
      }

      RequestQueue.Request request = session.requestQueue().prepare();
      if (request == null) {
         throw new IllegalStateException();
      }
      request.startTime = System.nanoTime();
      request.sequence = session.currentSequence();
      request.exceptionHandler = h.handleException;
      request.request = httpRequest;
      if (timeout > 0) {
         // TODO alloc!
         request.timeoutFuture = session.executor().schedule(request, timeout, TimeUnit.MILLISECONDS);
      }
      if (headerAppenders != null) {
         for (BiConsumer<Session, HttpRequest> headerAppender : headerAppenders) {
            headerAppender.accept(session, httpRequest);
         }
      }

      // alloc-free below
      httpRequest.statusHandler(h.handleStatus);
      httpRequest.headerHandler(h.handleHeader);
      httpRequest.exceptionHandler(h.handleException);
      httpRequest.bodyPartHandler(h.handleBodyPart);
      httpRequest.endHandler(h.handleEnd);
      httpRequest.rawBytesHandler(h.handleRawBytes);

      if (trace) {
         log.trace("HTTP {} to {}", method, path);
      }
      httpRequest.end();
      session.currentSequence().statistics(session).incrementRequests();
      return true;
   }

   @Override
   public void reserve(Session session) {
      handler.reserve(session);
   }

   public static class Builder extends BaseStepBuilder {
      private HttpMethod method;
      private SerializableFunction<Session, String> pathGenerator;
      private SerializableFunction<Session, ByteBuf> bodyGenerator;
      private List<SerializableBiConsumer<Session, HttpRequest>> headerAppenders = new ArrayList<>();
      private long timeout = Long.MIN_VALUE;
      private HttpResponseHandler.Builder handler = new HttpResponseHandler.Builder(this);

      public Builder(BaseSequenceBuilder parent, HttpMethod method) {
         super(parent);
         this.method = method;
      }

      public Builder method(HttpMethod method) {
         this.method = method;
         return this;
      }

      public Builder path(String path) {
         return pathGenerator(s -> path);
      }

      public Builder pathGenerator(SerializableFunction<Session, String> pathGenerator) {
         if (this.pathGenerator != null) {
            throw new IllegalStateException("Path generator already set.");
         }
         this.pathGenerator = pathGenerator;
         return this;
      }

      public Builder bodyGenerator(SerializableFunction<Session, ByteBuf> bodyGenerator) {
         if (this.bodyGenerator != null) {
            throw new IllegalStateException("Body generator already set.");
         }
         this.bodyGenerator = bodyGenerator;
         return this;
      }

      public Builder headerAppender(SerializableBiConsumer<Session, HttpRequest> headerAppender) {
         headerAppenders.add(headerAppender);
         return this;
      }

      public Builder timeout(long timeout, TimeUnit timeUnit) {
         if (timeout <= 0) {
            throw new IllegalArgumentException("Timeout must be positive!");
         } else if (this.timeout != Long.MIN_VALUE) {
            throw new IllegalStateException("Timeout already set!");
         }
         this.timeout = timeUnit.toMillis(timeout);
         return this;
      }

      public Builder timeout(String timeout) {
         return timeout(Util.parseToMillis(timeout), TimeUnit.MILLISECONDS);
      }

      public HttpResponseHandler.Builder handler() {
         return handler;
      }

      @Override
      public List<Step> build() {
         SerializableBiConsumer<Session, HttpRequest>[] headerAppenders =
               this.headerAppenders.isEmpty() ? null : this.headerAppenders.toArray(new SerializableBiConsumer[0]);
         return Collections.singletonList(new HttpRequestStep(method, pathGenerator, bodyGenerator, headerAppenders, timeout, handler.build()));
      }
   }
}
