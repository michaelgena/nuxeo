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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.nuxeo.lib.stream.log.LogLag;
import org.nuxeo.lib.stream.log.LogManager;

/**
 * @since 9.3
 */
public class LagCommand extends Command {

    protected static final String NAME = "lag";

    protected boolean verbose = false;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void updateOptions(Options options) {
        options.addOption(
                Option.builder("l").longOpt("log-name").desc("Log name").hasArg().argName("LOG_NAME").build());
        options.addOption(Option.builder().longOpt("verbose").desc("Display lag for each partition").build());
    }

    @Override
    public boolean run(LogManager manager, CommandLine cmd) throws InterruptedException {
        String name = cmd.getOptionValue("log-name");
        verbose = cmd.hasOption("verbose");
        if (name != null) {
            lag(manager, name);
        } else {
            lag(manager);
        }
        return true;
    }

    protected void lag(LogManager manager) {
        System.out.println("# " + manager);
        for (String name : manager.listAll()) {
            lag(manager, name);
        }
    }

    protected void lag(LogManager manager, String name) {
        System.out.println("## Log: " + name + " partitions: " + manager.getAppender(name).size());
        List<String> consumers = manager.listConsumerGroups(name);
        if (verbose && consumers.isEmpty()) {
            // add a fake group to get info on end positions
            consumers.add("tools");
        }
        consumers.forEach(group -> renderLag(group, manager.getLagPerPartition(name, group)));
    }

    protected void renderLag(String group, List<LogLag> lags) {
        System.out.println("### Group: " + group);
        System.out.println(
                "| partition | lag | pos | end | posOffset | endOffset |\n" + "| --- | ---: | ---: | ---: | ---: |");
        LogLag all = LogLag.of(lags);
        System.out.println(String.format("|All|%d|%d|%d|%d|%d|", all.lag(), all.lower(), all.upper(), all.lowerOffset(),
                all.upperOffset()));
        if (verbose && lags.size() > 1) {
            AtomicInteger i = new AtomicInteger();
            lags.forEach(lag -> System.out.println(String.format("|%s|%d|%d|%d|%d|%d|", i.getAndIncrement(), lag.lag(),
                    lag.lower(), lag.upper(), lag.lowerOffset(), lag.upperOffset())));
        }
    }

}
