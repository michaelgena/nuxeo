/*
 * (C) Copyright 2017 Nuxeo SA (http://nuxeo.com/) and others.
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
 *     bdelbosc
 */
package org.nuxeo.lib.stream.tools.command;

import static org.nuxeo.lib.stream.tools.command.LatencyTrackerComputation.decodeKey;
import static org.nuxeo.lib.stream.tools.command.PositionCommand.FIRST_READ_TIMEOUT;
import static org.nuxeo.lib.stream.tools.command.PositionCommand.READ_TIMEOUT;
import static org.nuxeo.lib.stream.tools.command.PositionCommand.getTimestampFromDate;
import static org.nuxeo.lib.stream.tools.command.TrackerCommand.ALL_LOGS;
import static org.nuxeo.lib.stream.tools.command.TrackerCommand.DEFAULT_LATENCIES_LOG;
import static org.nuxeo.lib.stream.tools.command.TrackerCommand.INTERNAL_LOG_PREFIX;

import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.jetbrains.annotations.NotNull;
import org.nuxeo.lib.stream.computation.Record;
import org.nuxeo.lib.stream.computation.Watermark;
import org.nuxeo.lib.stream.log.Latency;
import org.nuxeo.lib.stream.log.LogManager;
import org.nuxeo.lib.stream.log.LogOffset;
import org.nuxeo.lib.stream.log.LogRecord;
import org.nuxeo.lib.stream.log.LogTailer;
import org.nuxeo.lib.stream.log.internals.LogPartitionGroup;

/**
 * @since 10.1
 */
public class RestoreCommand extends Command {

    protected static final String NAME = "restore";

    protected static final String GROUP = "tools";

    protected boolean verbose = false;

    protected String input;

    protected List<String> logNames;

    protected long date;

    protected boolean dryRun;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void updateOptions(Options options) {
        options.addOption(Option.builder("l")
                                .longOpt("log-name")
                                .desc("Restore consumers positions for this LOG, must be a computation Record, "
                                        + "can be a comma separated list of log names or ALL")
                                .required()
                                .hasArg()
                                .argName("LOG_NAME")
                                .build());
        options.addOption(Option.builder("i")
                                .longOpt("log-input")
                                .desc("Log name of the input default to " + DEFAULT_LATENCIES_LOG)
                                .hasArg()
                                .argName("LOG_OUTPUT")
                                .build());
        options.addOption(Option.builder()
                                .longOpt("to-date")
                                .desc("Sets the committed positions as they where at a specific date."
                                        + " The date is specified in ISO-8601 format, eg. " + Instant.now())
                                .hasArg()
                                .argName("DATE")
                                .build());
        options.addOption(Option.builder().longOpt("verbose").build());
        options.addOption(Option.builder().longOpt("dry-run").desc("Do not change any position").build());
    }

    @Override
    public boolean run(LogManager manager, CommandLine cmd) throws InterruptedException {
        logNames = getLogNames(manager, cmd.getOptionValue("log-name"));
        input = cmd.getOptionValue("log-input");
        date = getTimestampFromDate(cmd.getOptionValue("to-date"));
        verbose = cmd.hasOption("verbose");
        dryRun = cmd.hasOption("dry-run");

        return restorePosition(manager);
    }

    protected boolean restorePosition(LogManager manager) throws InterruptedException {
        Map<LogPartitionGroup, Latency> latencies = readLatencies(manager);
        Map<LogPartitionGroup, LogOffset> offsets = searchOffsets(manager, latencies);
        if (dryRun) {
            System.out.println("# Dry run mode returning without doing any changes");
            return true;
        }
        updatePositions(manager, offsets);
        return true;
    }

    protected void updatePositions(LogManager manager, Map<LogPartitionGroup, LogOffset> offsets) {
        offsets.forEach((key, offset) -> updatePosition(manager, key, offset));
    }

    protected void updatePosition(LogManager manager, LogPartitionGroup key, LogOffset offset) {
        if (offset == null) {
            return;
        }
        System.out.println("# Commit : " + key);
        try (LogTailer<Record> tailer = manager.createTailer(key.group, key.getLogPartition())) {
            tailer.seek(offset);
            tailer.commit();
        }
    }

    protected Map<LogPartitionGroup, LogOffset> searchOffsets(LogManager manager,
            Map<LogPartitionGroup, Latency> latencies) throws InterruptedException {
        Map<LogPartitionGroup, LogOffset> ret = new HashMap<>(latencies.size());
        System.out.println("# Searching offsets matching the latencies");
        for (LogPartitionGroup key : latencies.keySet()) {
            ret.put(key, findOffset(manager, key, latencies.get(key)));
        }
        return ret;
    }

    protected LogOffset findOffset(LogManager manager, LogPartitionGroup key, Latency latency)
            throws InterruptedException {
        long targetWatermark = latency.lower();
        String targetKey = latency.key();
        try (LogTailer<Record> tailer = manager.createTailer(key.group, key.getLogPartition())) {
            for (LogRecord<Record> rec = tailer.read(FIRST_READ_TIMEOUT); rec != null; rec = tailer.read(
                    READ_TIMEOUT)) {
                if (targetKey != null && !targetKey.equals(rec.message().key)) {
                    continue;
                }
                long timestamp = Watermark.ofValue(rec.message().watermark).getTimestamp();
                if (targetWatermark == timestamp) {
                    System.out.println("Offset found: " + key + ": " + rec.offset());
                    return rec.offset().nextOffset();
                }
            }
        }
        System.err.println("No offset found for: " + key);
        return null;
    }

    @NotNull
    private Map<LogPartitionGroup, Latency> readLatencies(LogManager manager) throws InterruptedException {
        Map<LogPartitionGroup, Latency> latencies = new HashMap<>();
        System.out.println("# Reading latencies from: " + input + " ...");
        try (LogTailer<Record> tailer = manager.createTailer(GROUP, input)) {
            for (LogRecord<Record> rec = tailer.read(FIRST_READ_TIMEOUT); rec != null; rec = tailer.read(
                    READ_TIMEOUT)) {
                long timestamp = Watermark.ofValue(rec.message().watermark).getTimestamp();
                if (date > 0 && timestamp > date) {
                    continue;
                }
                LogPartitionGroup key = decodeKey(rec.message().key);
                if (!logNames.contains(key.name)) {
                    continue;
                }
                Latency latency = decodeLatency(rec.message().data);
                if (latency != null) {
                    latencies.put(key, latency);
                }
            }
        }
        System.out.println("# Latencies found: ");
        latencies.forEach((key, latency) -> System.out.println(String.format("%s: %s", key, latency)));
        return latencies;
    }

    protected Latency decodeLatency(byte[] data) {
        try {
            return Latency.fromJson(new String(data, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            System.err.println("Cannot decode message" + e.getMessage() + " " + Arrays.toString(data));
        }
        return null;
    }

    protected List<String> getLogNames(LogManager manager, String names) {
        if (ALL_LOGS.equals(names.toLowerCase())) {
            return manager.listAll().stream().filter(name -> !name.startsWith(INTERNAL_LOG_PREFIX)).collect(
                    Collectors.toList());
        }
        List<String> ret = Arrays.asList(names.split(","));
        for (String name : ret) {
            if (!manager.exists(name)) {
                throw new IllegalArgumentException("Unknown log name: " + name);
            }
        }
        return ret;
    }

}
