/*
 * Copyright (C) 2020 Grakn Labs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package grakn.core.rocks;

import grakn.core.Grakn;
import grakn.core.common.concurrent.ManagedReadWriteLock;
import grakn.core.common.exception.GraknException;
import grakn.core.common.iterator.ResourceIterator;
import grakn.core.common.parameters.Arguments;
import grakn.core.common.parameters.Context;
import grakn.core.common.parameters.Options;
import grakn.core.concept.ConceptManager;
import grakn.core.graph.DataGraph;
import grakn.core.graph.GraphManager;
import grakn.core.graph.SchemaGraph;
import grakn.core.graph.util.KeyGenerator;
import grakn.core.graph.util.Storage;
import grakn.core.query.QueryManager;
import grakn.core.reasoner.Reasoner;
import grakn.core.reasoner.ReasonerCache;
import grakn.core.traversal.TraversalCache;
import grakn.core.traversal.TraversalEngine;
import org.rocksdb.AbstractImmutableNativeReference;
import org.rocksdb.OptimisticTransactionOptions;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDBException;
import org.rocksdb.Snapshot;
import org.rocksdb.Transaction;
import org.rocksdb.WriteOptions;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

import static grakn.common.util.Objects.className;
import static grakn.core.common.collection.Bytes.bytesHavePrefix;
import static grakn.core.common.exception.ErrorMessage.Internal.ILLEGAL_CAST;
import static grakn.core.common.exception.ErrorMessage.Transaction.ILLEGAL_COMMIT;
import static grakn.core.common.exception.ErrorMessage.Transaction.SESSION_DATA_VIOLATION;
import static grakn.core.common.exception.ErrorMessage.Transaction.SESSION_SCHEMA_VIOLATION;
import static grakn.core.common.exception.ErrorMessage.Transaction.TRANSACTION_CLOSED;
import static grakn.core.graph.util.Encoding.SCHEMA_GRAPH_STORAGE_REFRESH_RATE;

public abstract class RocksTransaction implements Grakn.Transaction {

    private static final byte[] EMPTY_ARRAY = new byte[]{};

    final RocksSession session;
    final Arguments.Transaction.Type type;
    final Context.Transaction context;
    GraphManager graphMgr;
    Rocks rocks;
    ConceptManager conceptMgr;
    TraversalEngine traversalEng;
    Reasoner reasoner;
    QueryManager queryMgr;
    AtomicBoolean isOpen;

    private RocksTransaction(RocksSession session,
                             Arguments.Transaction.Type type,
                             Options.Transaction options) {
        this.type = type;
        this.session = session;
        context = new Context.Transaction(session.context(), options).type(type);
        rocks = new Rocks();
    }

    static class Cache {

        private final TraversalCache traversalCache;
        private final ReasonerCache reasonerCache;
        private final SchemaGraph schemaGraph;

        Cache(SchemaGraph schemaGraph) {
            this.schemaGraph = schemaGraph;
            traversalCache = new TraversalCache();
            reasonerCache = new ReasonerCache();
        }

        public TraversalCache traversal() {
            return traversalCache;
        }

        public ReasonerCache reasoner() {
            return reasonerCache;
        }

        public SchemaGraph schemaGraph() {
            return schemaGraph;
        }
    }

    void initialise(GraphManager graphMgr, Cache cache) {
        conceptMgr = new ConceptManager(graphMgr);
        traversalEng = new TraversalEngine(graphMgr, cache.traversal());
        reasoner = new Reasoner(conceptMgr, traversalEng, cache.reasoner());
        queryMgr = new QueryManager(conceptMgr, reasoner, context);
        isOpen = new AtomicBoolean(true);
    }

    public abstract CoreStorage storage();

    public Context.Transaction context() {
        return context;
    }

    @Override
    public Arguments.Transaction.Type type() {
        return type;
    }

    @Override
    public Options.Transaction options() {
        return context.options();
    }

    @Override
    public boolean isOpen() {
        return this.isOpen.get();
    }

    @Override
    public QueryManager query() {
        if (!isOpen.get()) throw new GraknException(TRANSACTION_CLOSED);
        return queryMgr;
    }

    @Override
    public ConceptManager concepts() {
        if (!isOpen.get()) throw new GraknException(TRANSACTION_CLOSED);
        return conceptMgr;
    }

    public Reasoner reasoner() {
        if (!isOpen.get()) throw new GraknException(TRANSACTION_CLOSED);
        return reasoner;
    }

    @Override
    public void rollback() {
        try {
            graphMgr.clear();
            rocks.transaction.rollback();
        } catch (RocksDBException e) {
            throw new GraknException(e);
        }
    }

    void closeResources() {
        closeStorage();
        rocks.close();
        session.remove(this);
    }

    abstract void closeStorage();

    boolean isSchema() {
        return false;
    }

    boolean isData() {
        return false;
    }

    RocksTransaction.Schema asSchema() {
        throw GraknException.of(ILLEGAL_CAST.message(className(this.getClass()), className(RocksTransaction.Schema.class)));
    }

    RocksTransaction.Data asData() {
        throw GraknException.of(ILLEGAL_CAST.message(className(this.getClass()), className(RocksTransaction.Data.class)));
    }

    class Rocks {
        final WriteOptions writeOptions;
        final ReadOptions readOptions;
        final OptimisticTransactionOptions transactionOptions;
        final Transaction transaction;
        final Snapshot snapshot;

        Rocks() {
            writeOptions = new WriteOptions();
            transactionOptions = new OptimisticTransactionOptions().setSetSnapshot(true);
            transaction = session.rocks().beginTransaction(writeOptions, transactionOptions);
            snapshot = transaction.getSnapshot();
            readOptions = new ReadOptions().setSnapshot(snapshot);
        }

        void close() {
            readOptions.close();
            snapshot.close();
            transaction.close();
            transactionOptions.close();
            writeOptions.close();
        }
    }

    static class Schema extends RocksTransaction {

        private final Cache cache;
        private boolean mayClose;

        SchemaCoreStorage storage;

        Schema(RocksSession.Schema session, Arguments.Transaction.Type type, Options.Transaction options) {
            super(session, type, options);
            storage = new SchemaCoreStorage();
            final SchemaGraph schemaGraph = new SchemaGraph(storage, type.isRead());
            final DataGraph dataGraph = new DataGraph(storage, schemaGraph);
            graphMgr = new GraphManager(schemaGraph, dataGraph);
            cache = new Cache(schemaGraph);
            initialise(graphMgr, cache);
            mayClose = false;
        }

        @Override
        boolean isSchema() {
            return true;
        }

        @Override
        RocksTransaction.Schema asSchema() {
            return this;
        }

        public Cache cache() {
            return cache;
        }

        SchemaGraph graph() {
            return graphMgr.schema();
        }

        @Override
        public CoreStorage storage() {
            if (!isOpen.get()) throw new GraknException(TRANSACTION_CLOSED);
            return storage;
        }

        /**
         * Commits any writes captured in the transaction into storage.
         *
         * If the transaction was opened as a {@code READ} transaction, then this
         * operation will throw an exception. If this transaction has been committed,
         * it cannot be committed again. If it has not been committed, then it will
         * flush all changes in the graph into storage by calling {@code graph.commit()},
         * which may result in acquiring a lock on the storage to confirm that the data
         * will be committed into storage. The operation will then continue to commit
         * all the writes into RocksDB by calling {@code rocksTransaction.commit()}.
         * If the operation reaches this state, then the RocksDB commit was successful.
         * We then need let go of the transaction that this resources of hold.
         *
         * If a lock was acquired from calling {@code graph.commit()} then we should
         * let inform the graph by confirming whether the RocksDB commit was successful
         * or not.
         */
        @Override
        public void commit() {
            if (isOpen.compareAndSet(true, false)) {
                long lock = 0;
                try {
                    if (type.isRead()) throw new GraknException(ILLEGAL_COMMIT);
                    else if (graphMgr.data().isModified()) throw new GraknException(SESSION_SCHEMA_VIOLATION);

                    // We disable RocksDB indexing of uncommitted writes, as we're only about to write and never again reading
                    // TODO: We should benchmark this
                    rocks.transaction.disableIndexing();
                    conceptMgr.validateTypes();
                    graphMgr.schema().commit();
                    lock = session.database.dataReadSchemaLock().writeLock();
                    rocks.transaction.commit();
                } catch (RocksDBException e) {
                    rollback();
                    throw new GraknException(e);
                } finally {
                    session.database.closeCachedSchemaGraph();
                    if (lock > 0) session.database.dataReadSchemaLock().unlockWrite(lock);
                    graphMgr.clear();
                    closeResources();
                }
            } else {
                throw new GraknException(TRANSACTION_CLOSED);
            }
        }

        @Override
        public void close() {
            if (isOpen.compareAndSet(true, false)) {
                closeResources();
            }
        }

        @Override
        void closeStorage() {
            storage.close();
        }

        public void mayClose() {
            mayClose = true;
        }

        class SchemaCoreStorage extends CoreStorage implements Storage.Schema {

            private final AtomicLong referenceCounter;
            private final AtomicInteger refreshCounter;

            SchemaCoreStorage() {
                super();
                referenceCounter = new AtomicLong();
                refreshCounter = new AtomicInteger();
            }

            @Override
            public void incrementReference() {
                referenceCounter.incrementAndGet();
            }

            @Override
            public void decrementReference() {
                if (referenceCounter.decrementAndGet() == 0 && mayClose) RocksTransaction.Schema.this.close();
            }

            @Override
            public void mayRefresh() {
                if (refreshCounter.incrementAndGet() == SCHEMA_GRAPH_STORAGE_REFRESH_RATE) {
                    refreshCounter.addAndGet(-1 * SCHEMA_GRAPH_STORAGE_REFRESH_RATE);
                    storage.refresh();
                }
            }

            public void refresh() {
                assert type.isRead();
                Rocks oldRocks = rocks;
                rocks = new Rocks();
                oldRocks.close();
            }
        }
    }

    public static class Data extends RocksTransaction {

        CoreStorage storage;

        public Data(RocksSession.Data session, Arguments.Transaction.Type type, Options.Transaction options) {
            super(session, type, options);
            storage = new CoreStorage();
            long lock = session.database.dataReadSchemaLock().readLock();
            Cache cache = session.database.cache();
            session.database.dataReadSchemaLock().unlockRead(lock);
            cache.schemaGraph().incrementReference();
            final DataGraph dataGraph = new DataGraph(storage, cache.schemaGraph());
            graphMgr = new GraphManager(cache.schemaGraph(), dataGraph);
            initialise(graphMgr, cache);
        }


        @Override
        boolean isData() {
            return true;
        }

        @Override
        RocksTransaction.Data asData() {
            return this;
        }

        @Override
        public CoreStorage storage() {
            if (!isOpen.get()) throw new GraknException(TRANSACTION_CLOSED);
            return storage;
        }

        /**
         * Commits any writes captured in the transaction into storage.
         *
         * If the transaction was opened as a {@code READ} transaction, then this
         * operation will throw an exception. If this transaction has been committed,
         * it cannot be committed again. If it has not been committed, then it will
         * flush all changes in the graph into storage by calling {@code graph.commit()},
         * which may result in acquiring a lock on the storage to confirm that the data
         * will be committed into storage. The operation will then continue to commit
         * all the writes into RocksDB by calling {@code rocksTransaction.commit()}.
         * If the operation reaches this state, then the RocksDB commit was successful.
         * We then need let go of the transaction that this resources of hold.
         *
         * If a lock was acquired from calling {@code graph.commit()} then we should
         * let inform the graph by confirming whether the RocksDB commit was successful
         * or not.
         */
        @Override
        public void commit() {
            if (isOpen.compareAndSet(true, false)) {
                try {
                    if (type.isRead()) throw new GraknException(ILLEGAL_COMMIT);
                    else if (graphMgr.schema().isModified()) throw new GraknException(SESSION_DATA_VIOLATION);

                    // We disable RocksDB indexing of uncommitted writes, as we're only about to write and never again reading
                    // TODO: We should benchmark this
                    rocks.transaction.disableIndexing();
                    conceptMgr.validateThings();
                    graphMgr.data().commit();
                    rocks.transaction.commit();
                } catch (RocksDBException e) {
                    rollback();
                    throw new GraknException(e);
                } finally {
                    graphMgr.data().clear();
                    graphMgr.schema().mayRefreshStorage();
                    closeResources();

                }
            } else {
                throw new GraknException(TRANSACTION_CLOSED);
            }
        }

        @Override
        public void close() {
            if (isOpen.compareAndSet(true, false)) {
                graphMgr.schema().decrementReference();
                closeResources();
            }
        }

        @Override
        void closeStorage() {
            storage.close();
        }
    }

    class CoreStorage implements Storage {

        private final ManagedReadWriteLock readWriteLock;
        private final Set<RocksIterator<?>> iterators;
        private final ConcurrentLinkedQueue<org.rocksdb.RocksIterator> recycled;
        private final AtomicBoolean isOpen;

        CoreStorage() {
            readWriteLock = new ManagedReadWriteLock();
            iterators = ConcurrentHashMap.newKeySet();
            recycled = new ConcurrentLinkedQueue<>();
            isOpen = new AtomicBoolean(true);
        }

        @Override
        public boolean isOpen() {
            return isOpen.get();
        }

        @Override
        public KeyGenerator.Schema schemaKeyGenerator() {
            return session.schemaKeyGenerator();
        }

        @Override
        public KeyGenerator.Data dataKeyGenerator() {
            return session.dataKeyGenerator();
        }

        @Override
        public byte[] get(byte[] key) {
            validateTransactionIsOpen();
            try {
                // We don't need to check isOpen.get() as tx.commit() does not involve this method
                if (type.isWrite()) readWriteLock.lockRead();
                return rocks.transaction.get(rocks.readOptions, key);
            } catch (RocksDBException | InterruptedException e) {
                throw exception(e);
            } finally {
                if (type.isWrite()) readWriteLock.unlockRead();
            }
        }

        @Override
        public byte[] getLastKey(byte[] prefix) {
            validateTransactionIsOpen();
            final byte[] upperBound = Arrays.copyOf(prefix, prefix.length);
            upperBound[upperBound.length - 1] = (byte) (upperBound[upperBound.length - 1] + 1);
            assert upperBound[upperBound.length - 1] != Byte.MIN_VALUE;

            try (org.rocksdb.RocksIterator iterator = getInternalRocksIterator()) {
                iterator.seekForPrev(upperBound);
                if (bytesHavePrefix(iterator.key(), prefix)) return iterator.key();
                else return null;
            }
        }

        @Override
        public void delete(byte[] key) {
            validateTransactionIsOpen();
            try {
                if (isOpen.get()) readWriteLock.lockWrite();
                rocks.transaction.delete(key);
            } catch (RocksDBException | InterruptedException e) {
                throw exception(e);
            } finally {
                if (isOpen.get()) readWriteLock.unlockWrite();
            }
        }

        @Override
        public void put(byte[] key) {
            put(key, EMPTY_ARRAY);
        }

        @Override
        public void put(byte[] key, byte[] value) {
            validateTransactionIsOpen();
            try {
                if (isOpen.get()) readWriteLock.lockWrite();
                rocks.transaction.put(key, value);
            } catch (RocksDBException | InterruptedException e) {
                throw exception(e);
            } finally {
                if (isOpen.get()) readWriteLock.unlockWrite();
            }
        }

        @Override
        public void putUntracked(byte[] key) {
            putUntracked(key, EMPTY_ARRAY);
        }

        @Override
        public void putUntracked(byte[] key, byte[] value) {
            validateTransactionIsOpen();
            try {
                readWriteLock.lockWrite();
                rocks.transaction.putUntracked(key, value);
            } catch (RocksDBException | InterruptedException e) {
                throw exception(e);
            } finally {
                if (isOpen()) readWriteLock.unlockWrite();
            }
        }

        @Override
        public <G> ResourceIterator<G> iterate(byte[] key, BiFunction<byte[], byte[], G> constructor) {
            validateTransactionIsOpen();
            final RocksIterator<G> iterator = new RocksIterator<>(this, key, constructor);
            iterators.add(iterator);
            return iterator;
        }

        @Override
        public GraknException exception(String message) {
            RocksTransaction.this.close();
            return new GraknException(message);
        }

        @Override
        public GraknException exception(Exception exception) {
            RocksTransaction.this.close();
            return new GraknException(exception);
        }

        @Override
        public GraknException exception(GraknException exception) {
            RocksTransaction.this.close();
            return exception;
        }

        void validateTransactionIsOpen() {
            if (!isOpen()) throw GraknException.of(TRANSACTION_CLOSED);
        }

        org.rocksdb.RocksIterator getInternalRocksIterator() {
            if (type.isRead()) {
                final org.rocksdb.RocksIterator iterator = recycled.poll();
                if (iterator != null) return iterator;
            }
            return rocks.transaction.getIterator(rocks.readOptions);
        }

        public void recycle(org.rocksdb.RocksIterator rocksIterator) {
            recycled.add(rocksIterator);
        }

        void remove(RocksIterator<?> iterator) {
            iterators.remove(iterator);
        }

        void close() {
            if (isOpen.compareAndSet(true, false)) {
                iterators.parallelStream().forEach(RocksIterator::close);
                recycled.forEach(AbstractImmutableNativeReference::close);
            }
        }
    }
}
