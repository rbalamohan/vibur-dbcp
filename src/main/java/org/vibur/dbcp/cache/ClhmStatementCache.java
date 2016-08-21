/**
 * Copyright 2015 Simeon Malchev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.vibur.dbcp.cache;

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import com.googlecode.concurrentlinkedhashmap.EvictionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vibur.dbcp.proxy.TargetInvoker;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;
import static org.vibur.dbcp.cache.StatementHolder.State.*;
import static org.vibur.dbcp.util.JdbcUtils.clearWarnings;
import static org.vibur.dbcp.util.JdbcUtils.quietClose;
import static org.vibur.objectpool.util.ArgumentValidation.forbidIllegalArgument;

/**
 * Implements and encapsulates all JDBC Statement caching functionality and logic. The cache implementation is
 * based on {@link ConcurrentLinkedHashMap}.
 *
 * @author Simeon Malchev
 */
public class ClhmStatementCache implements StatementCache {

    private static final Logger logger = LoggerFactory.getLogger(ClhmStatementCache.class);

    private final ConcurrentMap<ConnMethod, StatementHolder> statementCache;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public ClhmStatementCache(int maxSize) {
        forbidIllegalArgument(maxSize <= 0);
        statementCache = requireNonNull(buildStatementCache(maxSize));
    }

    protected ConcurrentMap<ConnMethod, StatementHolder> buildStatementCache(int maxSize) {
        return new ConcurrentLinkedHashMap.Builder<ConnMethod, StatementHolder>()
                .initialCapacity(maxSize)
                .maximumWeightedCapacity(maxSize)
                .listener(getListener())
                .build();
    }

    /**
     * Creates and returns a new EvictionListener for the CLHM. It is worth noting that this
     * EvictionListener is called in the context of the thread that has executed an insert (putIfAbsent)
     * operation which has increased the CLHM size above its maxSize - in which case the CLHM
     * evicts its LRU entry.
     *
     * @return a new EvictionListener for the CLHM
     */
    private EvictionListener<ConnMethod, StatementHolder> getListener() {
        return new EvictionListener<ConnMethod, StatementHolder>() {
            @Override
            public void onEviction(ConnMethod key, StatementHolder value) {
                if (value.state().getAndSet(EVICTED) == AVAILABLE)
                    quietClose(value.value());
                logger.trace("Evicted {}", value.value());
            }
        };
    }

    @Override
    public StatementHolder take(ConnMethod key, TargetInvoker invoker) throws Throwable {
        if (isClosed())
            return null;

        StatementHolder statement = statementCache.get(key);
        if (statement != null && statement.state().compareAndSet(AVAILABLE, IN_USE)) {
            logger.trace("Using cached statement for {}", key);
            return statement;
        }

        Statement rawStatement = (Statement) invoker.targetInvoke(key.getMethod(), key.getArgs());
        if (statement == null) { // there was no entry for the key, so we'll try to put a new one
            statement = new StatementHolder(rawStatement, new AtomicReference<>(IN_USE));
            if (statementCache.putIfAbsent(key, statement) == null)
                return statement; // the new entry was successfully put in the cache
        }
        return new StatementHolder(rawStatement, null);
    }

    @Override
    public void restore(StatementHolder statement, boolean clearWarnings) {
        if (statement.state() == null) // this statement is not in the cache
            return;
        if (isClosed()) {
            remove(statement.value());
            statement.state().set(EVICTED);
        }

        Statement rawStatement = statement.value();
        try {
            if (clearWarnings)
                clearWarnings(rawStatement);
            if (!statement.state().compareAndSet(IN_USE, AVAILABLE)) // just mark it as AVAILABLE if it was IN_USE
                quietClose(rawStatement); // and close it if it was already EVICTED while it was IN_USE
        } catch (SQLException e) {
            logger.debug("Couldn't clear warnings on {}", rawStatement, e);
            remove(rawStatement);
            quietClose(rawStatement); // close it always, disregards whether it was EVICTED or IN_USE
            statement.state().set(EVICTED);
        }
    }

    @Override
    public boolean remove(Statement rawStatement) {
        for (Map.Entry<ConnMethod, StatementHolder> entry : statementCache.entrySet()) {
            StatementHolder value = entry.getValue();
            if (value.value() == rawStatement) // comparing with == as these JDBC Statements are cached objects
                return statementCache.remove(entry.getKey(), value);
        }
        return false;
    }

    @Override
    public int removeAll(Connection rawConnection) {
        int removed = 0;
        for (Map.Entry<ConnMethod, StatementHolder> entry : statementCache.entrySet()) {
            ConnMethod key = entry.getKey();
            StatementHolder value = entry.getValue();
            if (key.getTarget() == rawConnection && statementCache.remove(key, value)) {
                quietClose(value.value());
                removed++;
            }
        }
        return removed;
    }

    /**
     * Closes this ClhmStatementCache and removes all entries from it.
     */
    @Override
    public void close() {
        if (closed.getAndSet(true))
            return;

        for (Map.Entry<ConnMethod, StatementHolder> entry : statementCache.entrySet()) {
            StatementHolder value = entry.getValue();
            statementCache.remove(entry.getKey(), value);
            quietClose(value.value());
        }
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }
}