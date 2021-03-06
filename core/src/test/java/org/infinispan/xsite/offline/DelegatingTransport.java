package org.infinispan.xsite.offline;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.LongConsumer;

import org.infinispan.remoting.transport.AbstractDelegatingTransport;
import org.infinispan.remoting.transport.BackupResponse;
import org.infinispan.remoting.transport.Transport;
import org.infinispan.remoting.transport.XSiteAsyncAckListener;
import org.infinispan.util.logging.Log;
import org.infinispan.xsite.BaseSiteUnreachableTest;
import org.infinispan.xsite.XSiteBackup;
import org.infinispan.xsite.XSiteReplicateCommand;

public class DelegatingTransport extends AbstractDelegatingTransport {

   volatile boolean fail;

   public DelegatingTransport(Transport actual) {
      super(actual);
   }

   @Override
   public void start() {
      //no-op; avoid re-start the transport again...
   }

   @Override
   public BackupResponse backupRemotely(final Collection<XSiteBackup> backups, XSiteReplicateCommand rpcCommand) throws Exception {
      return new BackupResponse() {

         final long creationTime = System.currentTimeMillis();

         @Override
         public void waitForBackupToFinish() throws Exception {
         }

         @Override
         public Map<String, Throwable> getFailedBackups() {
            if (fail) {
               Map<String, Throwable> result = new HashMap<String, Throwable>();
               for (XSiteBackup xSiteBackup : backups) {
                  result.put(xSiteBackup.getSiteName(), new TimeoutException());
               }
               return result;
            } else {
               return Collections.emptyMap();
            }
         }

         @Override
         public Set<String> getCommunicationErrors() {
            if (fail) {
               return Collections.singleton(BaseSiteUnreachableTest.NYC);
            } else {
               return Collections.emptySet();
            }
         }

         @Override
         public long getSendTimeMillis() {
            return creationTime;
         }

         @Override
         public boolean isEmpty() {
            return false;
         }

         @Override
         public void notifyFinish(LongConsumer timeElapsedConsumer) {
            //no-op
         }

         @Override
         public void notifyAsyncAck(XSiteAsyncAckListener listener) {
            //no-op
         }

         @Override
         public boolean isSync(String siteName) {
            for (XSiteBackup backup : backups) {
               if (backup.getSiteName().equals(siteName) && backup.isSync()) {
                  return true;
               }
            }
            return false;
         }
      };
   }

   @Override
   public Log getLog() {
      return actual.getLog();
   }
}
