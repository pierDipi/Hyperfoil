package io.sailrocket.api.session;

import java.util.List;

import io.netty.util.concurrent.EventExecutorGroup;
import io.sailrocket.api.collection.ConcurrentPool;
import io.sailrocket.api.config.Phase;
import io.sailrocket.api.statistics.Statistics;

public interface PhaseInstance {
   Phase definition();

   Status status();

   void proceed(EventExecutorGroup executorGroup);

   long absoluteStartTime();

   void start(EventExecutorGroup executorGroup);

   void finish();

   void tryTerminate();

   void terminate();

   // TODO better name
   void setComponents(ConcurrentPool<Session> sessionPool, List<Session> sessionList, Statistics[] statistics, PhaseChangeHandler phaseChangeHandler);

   void reserveSessions();

   void notifyFinished(Session session);

   void notifyTerminated(Session session);

   void setTerminated();

   void fail(Throwable error);

   Throwable getError();

   enum Status {
      NOT_STARTED,
      RUNNING,
      FINISHED,
      TERMINATING,
      TERMINATED;

      public boolean isFinished() {
         return this.ordinal() >= FINISHED.ordinal();
      }
   }
}
