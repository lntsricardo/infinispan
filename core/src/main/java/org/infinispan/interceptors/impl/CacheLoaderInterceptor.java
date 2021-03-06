package org.infinispan.interceptors.impl;

import static org.infinispan.persistence.manager.PersistenceManager.AccessMode.NOT_ASYNC;
import static org.infinispan.persistence.manager.PersistenceManager.AccessMode.SHARED;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.infinispan.Cache;
import org.infinispan.CacheSet;
import org.infinispan.CacheStream;
import org.infinispan.cache.impl.Caches;
import org.infinispan.commands.FlagAffectedCommand;
import org.infinispan.commands.SegmentSpecificCommand;
import org.infinispan.commands.VisitableCommand;
import org.infinispan.commands.functional.ReadOnlyKeyCommand;
import org.infinispan.commands.functional.ReadOnlyManyCommand;
import org.infinispan.commands.functional.ReadWriteKeyCommand;
import org.infinispan.commands.functional.ReadWriteKeyValueCommand;
import org.infinispan.commands.functional.ReadWriteManyCommand;
import org.infinispan.commands.functional.ReadWriteManyEntriesCommand;
import org.infinispan.commands.read.AbstractDataCommand;
import org.infinispan.commands.read.EntrySetCommand;
import org.infinispan.commands.read.GetAllCommand;
import org.infinispan.commands.read.GetCacheEntryCommand;
import org.infinispan.commands.read.GetKeyValueCommand;
import org.infinispan.commands.read.KeySetCommand;
import org.infinispan.commands.read.SizeCommand;
import org.infinispan.commands.remote.GetKeysInGroupCommand;
import org.infinispan.commands.write.ComputeCommand;
import org.infinispan.commands.write.ComputeIfAbsentCommand;
import org.infinispan.commands.write.InvalidateCommand;
import org.infinispan.commands.write.PutKeyValueCommand;
import org.infinispan.commands.write.RemoveCommand;
import org.infinispan.commands.write.ReplaceCommand;
import org.infinispan.commands.write.WriteCommand;
import org.infinispan.commons.time.TimeService;
import org.infinispan.commons.util.CloseableIterator;
import org.infinispan.commons.util.CloseableSpliterator;
import org.infinispan.commons.util.EnumUtil;
import org.infinispan.commons.util.IteratorMapper;
import org.infinispan.commons.util.RemovableCloseableIterator;
import org.infinispan.container.entries.CacheEntry;
import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.container.entries.MVCCEntry;
import org.infinispan.container.impl.EntryFactory;
import org.infinispan.container.impl.InternalDataContainer;
import org.infinispan.container.impl.InternalEntryFactory;
import org.infinispan.context.InvocationContext;
import org.infinispan.context.impl.FlagBitSets;
import org.infinispan.distribution.ch.KeyPartitioner;
import org.infinispan.distribution.group.impl.GroupFilter;
import org.infinispan.distribution.group.impl.GroupManager;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.annotations.Start;
import org.infinispan.factories.impl.ComponentRef;
import org.infinispan.jmx.annotations.DisplayType;
import org.infinispan.jmx.annotations.MBean;
import org.infinispan.jmx.annotations.ManagedAttribute;
import org.infinispan.jmx.annotations.ManagedOperation;
import org.infinispan.jmx.annotations.MeasurementType;
import org.infinispan.jmx.annotations.Parameter;
import org.infinispan.persistence.spi.MarshallableEntry;
import org.infinispan.notifications.cachelistener.CacheNotifier;
import org.infinispan.persistence.internal.PersistenceUtil;
import org.infinispan.persistence.manager.PersistenceManager;
import org.infinispan.stream.impl.local.AbstractLocalCacheStream;
import org.infinispan.stream.impl.local.EntryStreamSupplier;
import org.infinispan.stream.impl.local.KeyStreamSupplier;
import org.infinispan.stream.impl.local.LocalCacheStream;
import org.infinispan.stream.impl.local.PersistenceEntryStreamSupplier;
import org.infinispan.stream.impl.local.PersistenceKeyStreamSupplier;
import org.infinispan.stream.impl.spliterators.IteratorAsSpliterator;
import org.infinispan.util.EntryWrapper;
import org.infinispan.util.LazyConcatIterator;
import org.infinispan.util.concurrent.AggregateCompletionStage;
import org.infinispan.util.concurrent.CompletionStages;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;
import org.reactivestreams.Publisher;

import io.reactivex.Flowable;

/**
 * @since 9.0
 */
@MBean(objectName = "CacheLoader", description = "Component that handles loading entries from a CacheStore into memory.")
public class CacheLoaderInterceptor<K, V> extends JmxStatsCommandInterceptor {
   private static final Log log = LogFactory.getLog(CacheLoaderInterceptor.class);
   private static final boolean trace = log.isTraceEnabled();

   private final AtomicLong cacheLoads = new AtomicLong(0);
   private final AtomicLong cacheMisses = new AtomicLong(0);

   @Inject protected PersistenceManager persistenceManager;
   @Inject protected CacheNotifier notifier;
   @Inject protected EntryFactory entryFactory;
   @Inject TimeService timeService;
   @Inject InternalEntryFactory iceFactory;
   @Inject InternalDataContainer<K, V> dataContainer;
   @Inject GroupManager groupManager;
   @Inject ComponentRef<Cache<K, V>> cache;
   @Inject KeyPartitioner partitioner;

   private boolean activation;

   @Start
   public void start() {
      this.activation = cacheConfiguration.persistence().passivation();
   }

   @Override
   public Object visitPutKeyValueCommand(InvocationContext ctx, PutKeyValueCommand command)
         throws Throwable {
      return visitDataCommand(ctx, command);
   }

   @Override
   public Object visitGetKeyValueCommand(InvocationContext ctx, GetKeyValueCommand command)
         throws Throwable {
      return visitDataCommand(ctx, command);
   }

   @Override
   public Object visitGetCacheEntryCommand(InvocationContext ctx,
                                           GetCacheEntryCommand command) throws Throwable {
      return visitDataCommand(ctx, command);
   }


   @Override
   public Object visitGetAllCommand(InvocationContext ctx, GetAllCommand command)
         throws Throwable {
      CompletionStage<Void> aggregatedStage = null;
      Collection<?> keys = command.getKeys();
      if (keys != null && !keys.isEmpty()) {
         AggregateCompletionStage<Void> aggregateCompletionStage = CompletionStages.aggregateCompletionStage();
         for (Object key : command.getKeys()) {
            CompletionStage<Void> stage = loadIfNeeded(ctx, key, command);
            if (stage != null) {
               aggregateCompletionStage.dependsOn(stage);
            }
         }
         aggregatedStage = aggregateCompletionStage.freeze();
      }
      return asyncInvokeNext(ctx, command, aggregatedStage);
   }

   @Override
   public Object visitInvalidateCommand(InvocationContext ctx, InvalidateCommand command)
         throws Throwable {
      Object[] keys;
      CompletionStage<Void> aggregatedStage = null;
      if ((keys = command.getKeys()) != null && keys.length > 0) {
         AggregateCompletionStage<Void> aggregateCompletionStage = CompletionStages.aggregateCompletionStage();
         for (Object key : keys) {
            CompletionStage<Void> stage = loadIfNeeded(ctx, key, command);
            if (stage != null) {
               aggregateCompletionStage.dependsOn(stage);
            }
         }
         aggregatedStage = aggregateCompletionStage.freeze();
      }
      return asyncInvokeNext(ctx, command, aggregatedStage);
   }

   @Override
   public Object visitRemoveCommand(InvocationContext ctx, RemoveCommand command)
         throws Throwable {
      return visitDataCommand(ctx, command);
   }

   @Override
   public Object visitReplaceCommand(InvocationContext ctx, ReplaceCommand command)
         throws Throwable {
      return visitDataCommand(ctx, command);
   }

   @Override
   public Object visitComputeCommand(InvocationContext ctx, ComputeCommand command) throws Throwable {
      return visitDataCommand(ctx, command);
   }

   @Override
   public Object visitComputeIfAbsentCommand(InvocationContext ctx, ComputeIfAbsentCommand command) throws Throwable {
      return visitDataCommand(ctx, command);
   }

   private Object visitManyDataCommand(InvocationContext ctx, FlagAffectedCommand command, Collection<?> keys)
         throws Throwable {
      CompletionStage<Void> stage = null;
      for (Object key : keys) {
         stage = loadIfNeeded(ctx, key, command);
      }
      return asyncInvokeNext(ctx, command, stage);
   }

   private Object visitDataCommand(InvocationContext ctx, AbstractDataCommand command)
         throws Throwable {
      Object key;
      CompletionStage<Void> stage = null;
      if ((key = command.getKey()) != null) {
         stage = loadIfNeeded(ctx, key, command);
      }
      return asyncInvokeNext(ctx, command, stage);
   }

   @Override
   public Object visitGetKeysInGroupCommand(final InvocationContext ctx,
                                            GetKeysInGroupCommand command) throws Throwable {
      if (!command.isGroupOwner() || hasSkipLoadFlag(command)) {
         return invokeNext(ctx, command);
      }

      final Predicate<? super K> keyFilter = new GroupFilter<>(command.getGroupName(), groupManager)
                                                .and(k -> ctx.lookupEntry(k) == null);

      Publisher<MarshallableEntry<K, V>> publisher = persistenceManager.publishEntries(keyFilter, true, false,
            PersistenceManager.AccessMode.BOTH);
      Flowable.fromPublisher(publisher)
            .map(me -> PersistenceUtil.convert(me, iceFactory))
            .blockingForEach(ice -> entryFactory.wrapExternalEntry(ctx, ice.getKey(), ice, true, false));
      return invokeNext(ctx, command);
   }

   @Override
   public Object visitEntrySetCommand(InvocationContext ctx, EntrySetCommand command)
         throws Throwable {
      // Acquire the remote iteration flag and set it for all below - so they won't wrap unnecessarily
      boolean isRemoteIteration = command.hasAnyFlag(FlagBitSets.REMOTE_ITERATION);
      command.addFlags(FlagBitSets.REMOTE_ITERATION);
      return invokeNextThenApply(ctx, command, (rCtx, rCommand, rv) -> {
         if (hasSkipLoadFlag(command)) {
            // Continue with the existing throwable/return value
            return rv;
         }
         CacheSet<CacheEntry<K, V>> entrySet = (CacheSet<CacheEntry<K, V>>) rv;
         return new WrappedEntrySet(command, isRemoteIteration, entrySet);
      });
   }

   @Override
   public Object visitKeySetCommand(InvocationContext ctx, KeySetCommand command)
         throws Throwable {
      // Acquire the remote iteration flag and set it for all below - so they won't wrap unnecessarily
      boolean isRemoteIteration = command.hasAnyFlag(FlagBitSets.REMOTE_ITERATION);
      command.addFlags(FlagBitSets.REMOTE_ITERATION);
      return invokeNextThenApply(ctx, command, (rCtx, rCommand, rv) -> {
         if (hasSkipLoadFlag(command)) {
            // Continue with the existing throwable/return value
            return rv;
         }

         CacheSet<K> keySet = (CacheSet<K>) rv;
         return new WrappedKeySet(command, isRemoteIteration, keySet);
      });
   }

   @Override
   public Object visitReadOnlyKeyCommand(InvocationContext ctx, ReadOnlyKeyCommand command) throws Throwable {
      return visitDataCommand(ctx, command);
   }

   @Override
   public Object visitReadOnlyManyCommand(InvocationContext ctx, ReadOnlyManyCommand command)
         throws Throwable {
      return visitManyDataCommand(ctx, command, command.getKeys());
   }

   @Override
   public Object visitReadWriteKeyCommand(InvocationContext ctx, ReadWriteKeyCommand command)
         throws Throwable {
      return visitDataCommand(ctx, command);
   }

   @Override
   public Object visitReadWriteKeyValueCommand(InvocationContext ctx, ReadWriteKeyValueCommand command)
         throws Throwable {
      return visitDataCommand(ctx, command);
   }

   @Override
   public Object visitReadWriteManyCommand(InvocationContext ctx, ReadWriteManyCommand command) throws Throwable {
      return visitManyDataCommand(ctx, command, command.getAffectedKeys());
   }

   @Override
   public Object visitReadWriteManyEntriesCommand(InvocationContext ctx, ReadWriteManyEntriesCommand command) throws Throwable {
      return visitManyDataCommand(ctx, command, command.getAffectedKeys());
   }

   @Override
   public Object visitSizeCommand(InvocationContext ctx, SizeCommand command) throws Throwable {
      int size = trySizeOptimization(command.getFlagsBitSet());
      if (size >= 0) {
         return size;
      }
      return super.visitSizeCommand(ctx, command);
   }

   private int trySizeOptimization(long flagBitSet) {
      if (EnumUtil.containsAny(flagBitSet, FlagBitSets.SKIP_CACHE_LOAD | FlagBitSets.SKIP_SIZE_OPTIMIZATION)) {
         return -1;
      }
      // Get the size from any shared store that isn't async
      return persistenceManager.size(SHARED.and(NOT_ASYNC));
   }

   protected final boolean isConditional(WriteCommand cmd) {
      return cmd.isConditional();
   }

   protected final boolean hasSkipLoadFlag(FlagAffectedCommand cmd) {
      return cmd.hasAnyFlag(FlagBitSets.SKIP_CACHE_LOAD);
   }

   protected boolean canLoad(Object key) {
      return true;
   }

   /**
    * Loads from the cache loader the entry for the given key.  A found value is loaded into the current context.  The
    * method returns whether the value was found or not, or even if the cache loader was checked.
    * @param ctx The current invocation's context
    * @param key The key for the entry to look up
    * @param cmd The command that was called that now wants to query the cache loader
    * @return null or a CompletionStage that when complete all listeners will be notified
    * @throws Throwable
    */
   protected final CompletionStage<Void> loadIfNeeded(final InvocationContext ctx, Object key, final FlagAffectedCommand cmd) {
      if (skipLoad(cmd, key, ctx)) {
         return null;
      }

      return loadInContext(ctx, key, cmd);
   }

   private CompletionStage<Void> loadInContext(InvocationContext ctx, Object key, FlagAffectedCommand cmd) {
      final AtomicReference<Boolean> isLoaded = new AtomicReference<>();
      InternalCacheEntry<K, V> entry = PersistenceUtil.loadAndStoreInDataContainer(dataContainer,
            SegmentSpecificCommand.extractSegment(cmd, key, partitioner), persistenceManager, (K) key, ctx, timeService,
            isLoaded);
      Boolean isLoadedValue = isLoaded.get();
      if (trace) {
         log.tracef("Entry was loaded? %s", isLoadedValue);
      }
      if (getStatisticsEnabled()) {
         if (isLoadedValue == null) {
            // the entry was in data container, we haven't touched cache store
         } else if (isLoadedValue) {
            cacheLoads.incrementAndGet();
         } else {
            cacheMisses.incrementAndGet();
         }
      }

      CompletionStage<Void> stage = null;
      if (entry != null) {
         entryFactory.wrapExternalEntry(ctx, key, entry, true, cmd instanceof WriteCommand);

         if (isLoadedValue != null && isLoadedValue) {
            Object value = entry.getValue();
            // FIXME: There's no point to trigger the entryLoaded/Activated event twice.
            stage = sendNotification(key, value, true, ctx, cmd);
            if (CompletionStages.isCompletedSuccessfully(stage)) {
               stage = sendNotification(key, value, false, ctx, cmd);
            } else {
               stage = stage.thenCompose(v -> sendNotification(key, value, false, ctx, cmd));
            }
         }
      }
      CacheEntry contextEntry = ctx.lookupEntry(key);
      if (contextEntry instanceof MVCCEntry) {
         ((MVCCEntry) contextEntry).setLoaded(true);
      }
      return stage;
   }

   private boolean skipLoad(FlagAffectedCommand cmd, Object key, InvocationContext ctx) {
      CacheEntry e = ctx.lookupEntry(key);
      if (e == null) {
         if (trace) {
            log.tracef("Skip load for command %s. Entry is not in the context.", cmd);
         }
         return true;
      }
      if (e.getValue() != null) {
         if (trace) {
            log.tracef("Skip load for command %s. Entry %s (skipLookup=%s) has non-null value.", cmd, e, e.skipLookup());
         }
         return true;
      }
      if (e.skipLookup()) {
         if (trace) {
            log.tracef("Skip load for command %s. Entry %s (skipLookup=%s) is set to skip lookup.", cmd, e, e.skipLookup());
         }
         return true;
      }

      if (!cmd.hasAnyFlag(FlagBitSets.SKIP_OWNERSHIP_CHECK) && !canLoad(key)) {
         if (trace) {
            log.tracef("Skip load for command %s. Cannot load the key.", cmd);
         }
         return true;
      }

      boolean skip;
      if (cmd instanceof WriteCommand) {
         skip = skipLoadForWriteCommand((WriteCommand) cmd, key, ctx);
         if (trace) {
            log.tracef("Skip load for write command %s? %s", cmd, skip);
         }
      } else {
         //read command
         skip = hasSkipLoadFlag(cmd);
         if (trace) {
            log.tracef("Skip load for command %s?. %s", cmd, skip);
         }
      }
      return skip;
   }

   protected boolean skipLoadForWriteCommand(WriteCommand cmd, Object key, InvocationContext ctx) {
      // TODO loading should be mandatory if there are listeners for previous values
      if (cmd.loadType() != VisitableCommand.LoadType.DONT_LOAD) {
         if (hasSkipLoadFlag(cmd)) {
            log.tracef("Skipping load for command that reads existing values %s", cmd);
            return true;
         } else {
            return false;
         }
      }
      return true;
   }

   protected CompletionStage<Void> sendNotification(Object key, Object value, boolean pre,
         InvocationContext ctx, FlagAffectedCommand cmd) {
      CompletionStage<Void> stage = notifier.notifyCacheEntryLoaded(key, value, pre, ctx, cmd);
      if (activation) {
         if (CompletionStages.isCompletedSuccessfully(stage)) {
            stage = notifier.notifyCacheEntryActivated(key, value, pre, ctx, cmd);
         } else {
            stage = CompletionStages.allOf(stage, notifier.notifyCacheEntryActivated(key, value, pre, ctx, cmd));
         }
      }
      return stage;
   }

   @ManagedAttribute(
         description = "Number of entries loaded from cache store",
         displayName = "Number of cache store loads",
         measurementType = MeasurementType.TRENDSUP
   )
   @SuppressWarnings("unused")
   public long getCacheLoaderLoads() {
      return cacheLoads.get();
   }

   @ManagedAttribute(
         description = "Number of entries that did not exist in cache store",
         displayName = "Number of cache store load misses",
         measurementType = MeasurementType.TRENDSUP
   )
   @SuppressWarnings("unused")
   public long getCacheLoaderMisses() {
      return cacheMisses.get();
   }

   @Override
   @ManagedOperation(
         description = "Resets statistics gathered by this component",
         displayName = "Reset Statistics"
   )
   public void resetStatistics() {
      cacheLoads.set(0);
      cacheMisses.set(0);
   }

   @ManagedAttribute(
         description = "Returns a collection of cache loader types which are configured and enabled",
         displayName = "Returns a collection of cache loader types which are configured and enabled",
         displayType = DisplayType.DETAIL)
   /**
    * This method returns a collection of cache loader types (fully qualified class names) that are configured and enabled.
    */
   public Collection<String> getStores() {
      if (cacheConfiguration.persistence().usingStores()) {
         return persistenceManager.getStoresAsString();
      } else {
         return Collections.emptySet();
      }
   }

   @ManagedOperation(
         description = "Disable all stores of a given type, where type is a fully qualified class name of the cache loader to disable",
         displayName = "Disable all stores of a given type"
   )
   @SuppressWarnings("unused")
   /**
    * Disables a store of a given type.
    *
    * If the given type cannot be found, this is a no-op.  If more than one store of the same type is configured,
    * all stores of the given type are disabled.
    *
    * @param storeType fully qualified class name of the cache loader type to disable
    */
   public void disableStore(@Parameter(name = "storeType", description = "Fully qualified class name of a store implementation") String storeType) {
      persistenceManager.disableStore(storeType);
   }

   private ToIntFunction<Object> getSegmentMapper(Cache<?, ?> cache) {
      return partitioner::getSegment;
   }

   private abstract class AbstractLoaderSet<R> extends AbstractSet<R> implements CacheSet<R> {
      protected final CacheSet<R> cacheSet;
      protected final long commandFlagBitSet;

      AbstractLoaderSet(CacheSet<R> cacheSet, long commandFlagBitSet) {
         this.cacheSet = cacheSet;
         this.commandFlagBitSet = commandFlagBitSet;
      }

      protected abstract CloseableIterator<R> innerIterator();

      @Override
      public CloseableSpliterator<R> spliterator() {
         return new IteratorAsSpliterator.Builder<>(innerIterator())
               .setCharacteristics(Spliterator.CONCURRENT | Spliterator.DISTINCT | Spliterator.NONNULL).get();
      }

      @Override
      public void clear() {
         cache.wired().clear();
      }

      @Override
      public int size() {
         int size = trySizeOptimization(commandFlagBitSet);
         if (size >= 0) {
            return size;
         }

         long longSize = stream().count();
         if (longSize > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
         }
         return (int) longSize;
      }

      @Override
      public boolean isEmpty() {
         boolean empty = cacheSet.isEmpty();
         // Check the store if the data container was empty
         if (empty) {
            empty = Flowable.fromPublisher(persistenceManager.publishKeys(null, PersistenceManager.AccessMode.BOTH))
                  .blockingFirst(null) == null;
         }
         return empty;
      }

      @Override
      public CacheStream<R> stream() {
         return getStream(false);
      }

      @Override
      public CacheStream<R> parallelStream() {
         return getStream(true);
      }

      abstract protected CacheStream<R> getStream(boolean parallel);

      protected abstract AbstractLocalCacheStream.StreamSupplier<R, Stream<R>> supplier();
   }

   private class WrappedEntrySet extends AbstractLoaderSet<CacheEntry<K, V>> {
      private final Cache<K, V> cache;
      private final boolean isRemoteIteration;

      public WrappedEntrySet(EntrySetCommand command, boolean isRemoteIteration, CacheSet<CacheEntry<K, V>> entrySet) {
         super(entrySet, command.getFlagsBitSet());
         this.cache = Caches.getCacheWithFlags(CacheLoaderInterceptor.this.cache.wired(), command);
         this.isRemoteIteration = isRemoteIteration;
      }

      private Map.Entry<K, V> toEntry(Object obj) {
         if (obj instanceof Map.Entry) {
            return (Map.Entry) obj;
         } else {
            return null;
         }
      }

      @Override
      public boolean remove(Object o) {
         Map.Entry entry = toEntry(o);
         // Remove must be done by the cache
         return entry != null && cache.remove(entry.getKey(), entry.getValue());
      }

      @Override
      public CloseableIterator<CacheEntry<K, V>> iterator() {
         if (isRemoteIteration) {
            return innerIterator();
         }
         return new IteratorMapper<>(new RemovableCloseableIterator<>(innerIterator(),
               e -> cache.remove(e.getKey(), e.getValue())), e -> new EntryWrapper<>(cache, e));
      }

      protected CloseableIterator<CacheEntry<K, V>> innerIterator() {
         // This can be a HashSet since it is only written to from the local iterator which is only invoked
         // from user thread
         Set<K> seenKeys = new HashSet<>(cache.getAdvancedCache().getDataContainer().sizeIncludingExpired());
         CloseableIterator<CacheEntry<K, V>> localIterator = new IteratorMapper<>(cacheSet.iterator(), e -> {
            seenKeys.add(e.getKey());
            return e;
         });
         Flowable<MarshallableEntry<K, V>> flowable = Flowable.fromPublisher(persistenceManager.publishEntries(
               k -> !seenKeys.contains(k), true, true, PersistenceManager.AccessMode.BOTH));
         Publisher<CacheEntry<K, V>> publisher = flowable
               .map(me -> (CacheEntry<K, V>) PersistenceUtil.convert(me, iceFactory));
         // This way we don't subscribe to the flowable until after the first iterator is fully exhausted
         return new LazyConcatIterator<>(localIterator, () -> org.infinispan.util.Closeables.iterator(publisher, 128));
      }

      @Override
      protected AbstractLocalCacheStream.StreamSupplier<CacheEntry<K, V>, Stream<CacheEntry<K, V>>> supplier() {
         return new EntryStreamSupplier<>(cache, getSegmentMapper(cache), () -> StreamSupport.stream(spliterator(),
               false));
      }

      @Override
      public boolean contains(Object o) {
         boolean contains = false;
         if (o != null) {
            contains = cacheSet.contains(o);
            if (!contains) {
               Map.Entry<K, V> entry = toEntry(o);
               if (entry != null) {
                  MarshallableEntry<K, V> me = persistenceManager.loadFromAllStores(entry.getKey(), true, true);
                  if (me != null) {
                     contains = entry.getValue().equals(me.getValue());
                  }
               }
            }
         }
         return contains;
      }

      @Override
      protected CacheStream<CacheEntry<K, V>> getStream(boolean parallel) {
         return new LocalCacheStream<>(new PersistenceEntryStreamSupplier<>(cache, iceFactory, partitioner::getSegment,
               cacheSet.stream(), persistenceManager), parallel, cache.getAdvancedCache().getComponentRegistry());
      }
   }

   private class WrappedKeySet extends AbstractLoaderSet<K> implements CacheSet<K> {

      private final Cache<K, ?> cache;
      private final boolean isRemoteIteration;

      public WrappedKeySet(KeySetCommand command, boolean isRemoteIteration, CacheSet<K> keySet) {
         super(keySet, command.getFlagsBitSet());
         this.cache = Caches.getCacheWithFlags(CacheLoaderInterceptor.this.cache.wired(), command);
         this.isRemoteIteration = isRemoteIteration;
      }

      @Override
      public boolean remove(Object o) {
         // Remove must be done by the cache
         return o != null && cache.remove(o) != null;
      }

      @Override
      public CloseableIterator<K> iterator() {
         if (isRemoteIteration) {
            return innerIterator();
         }
         // Need to support remove of iterator
         return new RemovableCloseableIterator<>(innerIterator(), cache::remove);
      }

      @Override
      protected CloseableIterator<K> innerIterator() {
         // This can be a HashSet since it is only written to from the local iterator which is only invoked
         // from user thread
         Set<K> seenKeys = new HashSet<>(cache.getAdvancedCache().getDataContainer().sizeIncludingExpired());
         CloseableIterator<K> localIterator = new IteratorMapper<>(cacheSet.iterator(), k -> {
            seenKeys.add(k);
            return k;
         });
         Flowable<K> flowable = Flowable.fromPublisher(persistenceManager.publishKeys(
               k -> !seenKeys.contains(k), PersistenceManager.AccessMode.BOTH));
         // This way we don't subscribe to the flowable until after the first iterator is fully exhausted
         return new LazyConcatIterator<>(localIterator, () -> org.infinispan.util.Closeables.iterator(flowable, 128));
      }

      @Override
      protected AbstractLocalCacheStream.StreamSupplier<K, Stream<K>> supplier() {
         return new KeyStreamSupplier<>(cache, getSegmentMapper(cache), () -> StreamSupport.stream(spliterator(), false));
      }

      @Override
      public boolean contains(Object o) {
         boolean contains = false;
         if (o != null) {
            contains = cacheSet.contains(o);
            if (!contains) {
               MarshallableEntry<K, V> me = persistenceManager.loadFromAllStores(o, true, true);
               contains = me != null;
            }
         }
         return contains;
      }

      @Override
      protected CacheStream<K> getStream(boolean parallel) {
         return new LocalCacheStream<>(new PersistenceKeyStreamSupplier<>(cache, partitioner::getSegment,
               cacheSet.stream(), persistenceManager), parallel, cache.getAdvancedCache().getComponentRegistry());
      }
   }
}
