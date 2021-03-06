/*
 * (C) Copyright 2017 Nuxeo (http://nuxeo.com/) and others.
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
 *
 * Contributors:
 *     Florent Guillaume
 */
package org.nuxeo.runtime.migration;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.kv.KeyValueService;
import org.nuxeo.runtime.kv.KeyValueServiceImpl;
import org.nuxeo.runtime.kv.KeyValueStore;
import org.nuxeo.runtime.migration.MigrationDescriptor.MigrationStepDescriptor;
import org.nuxeo.runtime.model.ComponentContext;
import org.nuxeo.runtime.model.ComponentInstance;
import org.nuxeo.runtime.model.ComponentManager;
import org.nuxeo.runtime.model.DefaultComponent;
import org.nuxeo.runtime.model.SimpleContributionRegistry;
import org.nuxeo.runtime.pubsub.AbstractPubSubBroker;
import org.nuxeo.runtime.pubsub.SerializableMessage;

/**
 * Implementation for the Migration Service.
 * <p>
 * Data about migration status is stored in the "migration" Key/Value Store in the following format:
 *
 * <pre>
 * mymigration:lock         write lock, containing debug info about locker; set with a TTL
 * mymigration              the state of the migration, if not running
 * mymigration:step         the step of the migration, if running
 * mymigration:starttime    the migration step start time (milliseconds since epoch)
 * mymigration:pingtime     the migration step last ping time (milliseconds since epoch)
 * mymigration:message      the migration step current message
 * mymigration:num          the migration step current num
 * mymigration:total        the migration step current total
 * </pre>
 *
 * @since 9.3
 */
public class MigrationServiceImpl extends DefaultComponent implements MigrationService {

    // package-private to avoid synthetic accessor for access from nested class
    static final Log log = LogFactory.getLog(MigrationServiceImpl.class);

    public static final String KEYVALUE_STORE_NAME = "migration";

    public static final String CONFIG_XP = "configuration";

    public static final String LOCK = ":lock";

    public static final String STEP = ":step";

    public static final String START_TIME = ":starttime";

    public static final String PING_TIME = ":pingtime";

    public static final String PROGRESS_MESSAGE = ":message";

    public static final String PROGRESS_NUM = ":num";

    public static final String PROGRESS_TOTAL = ":total";

    public static final long WRITE_LOCK_TTL = 10; // 10 sec for a few k/v writes is plenty enough

    public static final String MIGRATION_INVAL_PUBSUB_TOPIC = "migrationinval";

    public static final String CLUSTERING_ENABLED_PROP = "repository.clustering.enabled";

    public static final String NODE_ID_PROP = "repository.clustering.id";

    protected static final Random RANDOM = new Random();

    protected final MigrationRegistry registry = new MigrationRegistry();

    protected MigrationThreadPoolExecutor executor;

    protected MigrationInvalidator invalidator;

    public static class MigrationRegistry extends SimpleContributionRegistry<MigrationDescriptor> {

        @Override
        public String getContributionId(MigrationDescriptor contrib) {
            return contrib.id;
        }

        public MigrationDescriptor getMigrationDescriptor(String id) {
            return getCurrentContribution(id);
        }

        public Map<String, MigrationDescriptor> getMigrationDescriptors() {
            return currentContribs;
        }

        @Override
        public boolean isSupportingMerge() {
            return true;
        }

        @Override
        public MigrationDescriptor clone(MigrationDescriptor orig) {
            return new MigrationDescriptor(orig);
        }

        @Override
        public void merge(MigrationDescriptor src, MigrationDescriptor dst) {
            dst.merge(src);
        }
    }

    public static class MigrationInvalidation implements SerializableMessage {

        private static final long serialVersionUID = 1L;

        public final String id;

        public MigrationInvalidation(String id) {
            this.id = id;
        }

        @Override
        public void serialize(OutputStream out) throws IOException {
            IOUtils.write(id, out, UTF_8);
        }

        public static MigrationInvalidation deserialize(InputStream in) throws IOException {
            String id = IOUtils.toString(in, UTF_8);
            return new MigrationInvalidation(id);
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "(" + id + ")";
        }
    }

    public class MigrationInvalidator extends AbstractPubSubBroker<MigrationInvalidation> {

        @Override
        public MigrationInvalidation deserialize(InputStream in) throws IOException {
            return MigrationInvalidation.deserialize(in);
        }

        @Override
        public void receivedMessage(MigrationInvalidation message) {
            String id = message.id;
            StatusChangeNotifier notifier = getStatusChangeNotifier(id);
            if (notifier == null) {
                log.error("Unknown migration id received in invalidation: " + id);
                return;
            }
            notifier.notifyStatusChange();
        }
    }

    protected static KeyValueStore getKeyValueStore() {
        KeyValueService service = Framework.getService(KeyValueService.class);
        Objects.requireNonNull(service, "Missing KeyValueService");
        return service.getKeyValueStore(KEYVALUE_STORE_NAME);
    }

    @Override
    public void registerContribution(Object contribution, String extensionPoint, ComponentInstance contributor) {
        switch (extensionPoint) {
        case CONFIG_XP:
            registerMigrationDescriptor((MigrationDescriptor) contribution);
            break;
        default:
            throw new RuntimeException("Unknown extension point: " + extensionPoint);
        }
    }

    @Override
    public void unregisterContribution(Object contribution, String extensionPoint, ComponentInstance contributor) {
        switch (extensionPoint) {
        case CONFIG_XP:
            unregisterMigrationDescriptor((MigrationDescriptor) contribution);
            break;
        }
    }

    public void registerMigrationDescriptor(MigrationDescriptor descriptor) {
        registry.addContribution(descriptor);
        log.info("Registered migration: " + descriptor.id);
    }

    public void unregisterMigrationDescriptor(MigrationDescriptor descriptor) {
        registry.removeContribution(descriptor);
        log.info("Unregistered migration: " + descriptor.id);
    }

    public Map<String, MigrationDescriptor> getMigrationDescriptors() {
        return registry.getMigrationDescriptors();
    }

    @Override
    public int getApplicationStartedOrder() {
        return KeyValueServiceImpl.APPLICATION_STARTED_ORDER + 10;
    }

    /**
     * Progress reporter that reports progress in the key/value store.
     *
     * @since 9.3
     */
    protected static class ProgressReporter {

        protected final String id;

        public ProgressReporter(String id) {
            this.id = id;
        }

        /**
         * Reports progress. If num or total are -2 then null is used.
         */
        public void reportProgress(String message, long num, long total, boolean ping) {
            KeyValueStore keyValueStore = getKeyValueStore();
            keyValueStore.put(id + PROGRESS_MESSAGE, message);
            keyValueStore.put(id + PROGRESS_NUM, num == -2 ? null : String.valueOf(num));
            keyValueStore.put(id + PROGRESS_TOTAL, total == -2 ? null : String.valueOf(total));
            keyValueStore.put(id + PING_TIME, ping ? String.valueOf(System.currentTimeMillis()) : null);
        }
    }

    /**
     * Migration context implementation that reports progress in the key/value store and can be shutdown.
     *
     * @since 9.3
     */
    protected static class MigrationContextImpl implements MigrationContext {

        protected final ProgressReporter progressReporter;

        protected volatile boolean shutdown;

        public MigrationContextImpl(ProgressReporter progressReporter) {
            this.progressReporter = progressReporter;
        }

        @Override
        public void reportProgress(String message, long num, long total) {
            progressReporter.reportProgress(message, num, total, true);
        }

        @Override
        public void requestShutdown() {
            shutdown = true;
        }

        @Override
        public boolean isShutdownRequested() {
            return shutdown || Thread.currentThread().isInterrupted();
        }
    }

    /**
     * Runnable for the migrator, that knows about the migration context.
     *
     * @since 9.3
     */
    protected static class MigratorWithContext implements Runnable {

        protected final Consumer<MigrationContext> migration;

        protected final MigrationContext migrationContext;

        protected final BiConsumer<MigrationContext, Throwable> afterMigration;

        public MigratorWithContext(Consumer<MigrationContext> migration, ProgressReporter progressReporter,
                BiConsumer<MigrationContext, Throwable> afterMigration) {
            this.migration = migration;
            this.migrationContext = new MigrationContextImpl(progressReporter);
            this.afterMigration = afterMigration;
        }

        @Override
        public void run() {
            migration.accept(migrationContext);
        }

        public void afterMigration(Throwable t) {
            afterMigration.accept(migrationContext, t);
        }

        public void requestShutdown() {
            migrationContext.requestShutdown();
        }
    }

    /**
     * Thread pool executor that records {@link Runnable}s to be able to request shutdown on them.
     *
     * @since 9.3
     */
    protected static class MigrationThreadPoolExecutor extends ThreadPoolExecutor {

        protected final List<MigratorWithContext> runnables = new CopyOnWriteArrayList<>();

        public MigrationThreadPoolExecutor() {
            // like Executors.newCachedThreadPool but with keepAliveTime of 0
            super(0, Integer.MAX_VALUE, 0, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());
        }

        @Override
        protected void beforeExecute(Thread thread, Runnable runnable) {
            runnables.add((MigratorWithContext) runnable);
        }

        @Override
        protected void afterExecute(Runnable runnable, Throwable t) {
            runnables.remove(runnable);
            ((MigratorWithContext) runnable).afterMigration(t);
        }

        public void requestShutdown() {
            runnables.forEach(migratorWithContext -> migratorWithContext.requestShutdown());
        }
    }

    @Override
    public void start(ComponentContext context) {
        if (Framework.isBooleanPropertyTrue(CLUSTERING_ENABLED_PROP)) {
            // register migration invalidator
            String nodeId = Framework.getProperty(NODE_ID_PROP);
            if (StringUtils.isBlank(nodeId)) {
                nodeId = String.valueOf(RANDOM.nextLong());
                log.warn("Missing cluster node id configuration, please define it explicitly "
                        + "(usually through repository.clustering.id). Using random cluster node id instead: "
                        + nodeId);
            } else {
                nodeId = nodeId.trim();
            }
            invalidator = new MigrationInvalidator();
            invalidator.initialize(MIGRATION_INVAL_PUBSUB_TOPIC, nodeId);
            log.info("Registered migration invalidator for node: " + nodeId);
        } else {
            log.info("Not registering a migration invalidator because clustering is not enabled");
        }

        executor = new MigrationThreadPoolExecutor();
        Framework.getRuntime().getComponentManager().addListener(new ComponentManager.Listener() {

            @Override
            public void beforeStop(ComponentManager mgr, boolean isStandby) {
                // flag all migration threads as shutdown requested, without interrupting them
                executor.requestShutdown();
            }

            @Override
            public void afterStop(ComponentManager mgr, boolean isStandby) {
                Framework.getRuntime().getComponentManager().removeListener(this);
            }
        });
    }

    @Override
    public void stop(ComponentContext context) throws InterruptedException {
        // interrupt all migration tasks
        executor.shutdownNow();
        executor.awaitTermination(10, TimeUnit.SECONDS); // wait 10s for termination
        executor = null;
    }

    @Override
    public MigrationStatus getStatus(String id) {
        MigrationDescriptor descr = registry.getMigrationDescriptor(id);
        if (descr == null) {
            return null; // migration unknown
        }
        KeyValueStore kv = getKeyValueStore();
        String state = kv.getString(id);
        if (state != null) {
            return new MigrationStatus(state);
        }
        String step = kv.getString(id + STEP);
        if (step == null) {
            state = descr.getDefaultState();
            return new MigrationStatus(state);
        }
        long startTime = Long.parseLong(kv.getString(id + START_TIME));
        long pingTime = Long.parseLong(kv.getString(id + PING_TIME));
        String progressMessage = kv.getString(id + PROGRESS_MESSAGE);
        long progressNum = Long.parseLong(kv.getString(id + PROGRESS_NUM));
        long progressTotal = Long.parseLong(kv.getString(id + PROGRESS_TOTAL));
        if (progressMessage == null) {
            progressMessage = "";
        }
        return new MigrationStatus(step, startTime, pingTime, progressMessage, progressNum, progressTotal);
    }

    @Override
    public void runStep(String id, String step) {
        MigrationDescriptor descr = registry.getMigrationDescriptor(id);
        if (descr == null) {
            throw new IllegalArgumentException("Unknown migration: " + id);
        }
        MigrationStepDescriptor stepDescr = descr.getSteps().get(step);
        if (stepDescr == null) {
            throw new IllegalArgumentException("Unknown step: " + step + " for migration: " + id);
        }
        Class<?> klass = stepDescr.getKlass();
        if (!Migrator.class.isAssignableFrom(klass)) {
            throw new RuntimeException("Invalid class not implementing Migrator: " + klass.getName() + " for step: "
                    + step + " for migration: " + id);
        }
        Migrator migrator;
        try {
            migrator = (Migrator) klass.getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        StatusChangeNotifier notifier = getStatusChangeNotifier(descr);

        ProgressReporter progressReporter = new ProgressReporter(id);

        // switch to running
        atomic(id, kv -> {
            String state = kv.getString(id);
            String currentStep = kv.getString(id + STEP);
            if (state == null && currentStep == null) {
                state = descr.getDefaultState();
                if (!descr.getStates().containsKey(state)) {
                    throw new RuntimeException("Invalid default state: " + state + " for migration: " + id);
                }
            } else if (state == null) {
                throw new IllegalArgumentException("Migration: " + id + " already running step: " + currentStep);
            }
            if (!descr.getStates().containsKey(state)) {
                throw new RuntimeException("Invalid current state: " + state + " for migration: " + id);
            }
            if (!stepDescr.getFromState().equals(state)) {
                throw new IllegalArgumentException(
                        "Invalid step: " + step + " for migration: " + id + " in state: " + state);
            }
            String time = String.valueOf(System.currentTimeMillis());
            kv.put(id + STEP, step);
            kv.put(id + START_TIME, time);
            progressReporter.reportProgress("", 0, -1, true);
            kv.put(id, (String) null);
        });

        // allow notification of running step
        notifier.notifyStatusChange();

        Consumer<MigrationContext> migration = migrationContext -> {
            Thread.currentThread().setName("Nuxeo-Migrator-" + id);
            migrator.run(migrationContext);
        };

        BiConsumer<MigrationContext, Throwable> afterMigration = (migrationContext, t) -> {
            if (t != null) {
                log.error("Exception during execution of step: " + step + " for migration: " + id, t);
            }
            // after the migrator is finished, change state, except if shutdown is requested or exception
            String state = (t != null || migrationContext.isShutdownRequested()) ? stepDescr.getFromState()
                    : stepDescr.getToState();
            atomic(id, kv -> {
                kv.put(id, state);
                kv.put(id + STEP, (String) null);
                kv.put(id + START_TIME, (String) null);
                progressReporter.reportProgress(null, -2, -2, false);
            });
            // allow notification of new state
            notifier.notifyStatusChange();
        };

        executor.execute(new MigratorWithContext(migration, progressReporter, afterMigration));
    }

    protected StatusChangeNotifier getStatusChangeNotifier(String id) {
        MigrationDescriptor descr = registry.getMigrationDescriptor(id);
        return descr == null ? null : getStatusChangeNotifier(descr);
    }

    protected StatusChangeNotifier getStatusChangeNotifier(MigrationDescriptor descr) {
        Class<?> klass = descr.getStatusChangeNotifierClass();
        if (klass == null) {
            throw new RuntimeException("Missing statusChangeNotifier for migration: " + descr.getId());
        }
        if (!StatusChangeNotifier.class.isAssignableFrom(klass)) {
            throw new RuntimeException("Invalid class not implementing StatusChangeNotifier: " + klass.getName()
                    + " for migration: " + descr.getId());
        }
        StatusChangeNotifier notifier;
        try {
            notifier = (StatusChangeNotifier) klass.getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return notifier;
    }

    /**
     * Executes something while setting a lock, retrying a few times if the lock is already set.
     */
    protected void atomic(String id, Consumer<KeyValueStore> consumer) {
        KeyValueStore kv = getKeyValueStore();
        String nodeid = Framework.getProperty("repository.clustering.id");
        for (int i = 0; i < 5; i++) {
            // the value of the lock is useful for debugging
            String value = Instant.now() + " node=" + nodeid;
            if (kv.compareAndSet(id + LOCK, null, value, WRITE_LOCK_TTL)) {
                try {
                    consumer.accept(kv);
                    return;
                } finally {
                    kv.put(id + LOCK, (String) null);
                }
            }
            try {
                Thread.sleep((long) (Math.random() * 100 * i));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        String currentLock = kv.getString(id + LOCK);
        throw new RuntimeException("Cannot lock for write migration: " + id + ", already locked: " + currentLock);
    }

}
