/*
 * Copyright 2018 Netflix, Inc.
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

package com.netflix.titus.testkit.perf.load;

import java.io.PrintWriter;
import javax.inject.Named;
import javax.inject.Singleton;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.netflix.archaius.config.MapConfig;
import com.netflix.archaius.guice.ArchaiusModule;
import com.netflix.governator.InjectorBuilder;
import com.netflix.governator.LifecycleInjector;
import com.netflix.governator.guice.jersey.GovernatorJerseySupportModule;
import com.netflix.governator.guice.jetty.Archaius2JettyModule;
import com.netflix.titus.common.runtime.TitusRuntime;
import com.netflix.titus.common.runtime.TitusRuntimes;
import com.netflix.titus.grpc.protogen.AgentManagementServiceGrpc;
import com.netflix.titus.grpc.protogen.JobManagementServiceGrpc;
import com.netflix.titus.runtime.connector.agent.AgentManagerConnectorModule;
import com.netflix.titus.runtime.connector.jobmanager.JobManagerConnectorModule;
import com.netflix.titus.testkit.embedded.cloud.connector.remote.SimulatedRemoteInstanceCloudConnector;
import com.netflix.titus.testkit.perf.load.rest.LoadJerseyModule;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NegotiationType;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.log4j.PropertyConfigurator;

/**
 *
 */
public class LoadGenerator {

    static {
        PropertyConfigurator.configure(LoadGenerator.class.getClassLoader().getResource("load-log4j.properties"));
    }

    private final LifecycleInjector injector;

    public LoadGenerator(String[] args) throws Exception {
        CommandLine cli = parseOptions(args);

        String hostName = cli.getOptionValue('H');
        int port = getIntOpt(cli, 'p', 8090);

        this.injector = InjectorBuilder.fromModules(new AbstractModule() {
            @Override
            protected void configure() {
                install(new ArchaiusModule() {
                    @Override
                    protected void configureArchaius() {
                        bindDefaultConfig().toInstance(MapConfig.builder()
                                .put("governator.jetty.embedded.port", "8999")
                                .put("governator.jetty.embedded.webAppResourceBase", "/")
                                .build());
                    }
                });

                bind(TitusRuntime.class).toInstance(TitusRuntimes.internal());

                install(new LoadModule());
                install(new LoadJerseyModule());
                install(new Archaius2JettyModule());
                install(new GovernatorJerseySupportModule());
                install(new JobManagerConnectorModule());
                install(new AgentManagerConnectorModule());
            }

            @Provides
            @Singleton
            JobManagementServiceGrpc.JobManagementServiceStub getJobManagementClient(ManagedChannel channel) {
                return JobManagementServiceGrpc.newStub(channel);
            }

            @Provides
            @Singleton
            AgentManagementServiceGrpc.AgentManagementServiceStub getAgentManagementClient(ManagedChannel channel) {
                return AgentManagementServiceGrpc.newStub(channel);
            }

            @Provides
            @Singleton
            ManagedChannel getManagedChannel() {
                return NettyChannelBuilder.forAddress(hostName, port)
                        .negotiationType(NegotiationType.PLAINTEXT)
                        .build();
            }

            @Provides
            @Singleton
            @Named(SimulatedRemoteInstanceCloudConnector.SIMULATED_CLOUD)
            ManagedChannel getSimulatorManagedChannel() {
                String simulatorHost;
                int simulatorPort;
                if (cli.hasOption('P')) {
                    simulatorHost = cli.getOptionValue('P');
                    simulatorPort = getIntOpt(cli, 'p', 7006);
                } else {
                    simulatorHost = "localhost";
                    simulatorPort = 7006;
                }
                return NettyChannelBuilder.forAddress(simulatorHost, simulatorPort)
                        .negotiationType(NegotiationType.PLAINTEXT)
                        .build();
            }
        }).createInjector();
    }

    private void tearDown() {
    }

    void run() throws InterruptedException {
        injector.awaitTermination();
    }

    private static int getIntOpt(CommandLine cli, char opt, long defaultValue) {
        try {
            return (int) (cli.hasOption(opt) ? (long) cli.getParsedOptionValue(Character.toString(opt)) : defaultValue);
        } catch (ParseException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static Options getOptions() {
        Options options = new Options();
        options.addOption(Option.builder("H").longOpt("host").argName("host_name").hasArg().required()
                .desc("TitusMaster host name")
                .build());
        options.addOption(Option.builder("p").longOpt("port").argName("port_number").hasArg().type(Number.class)
                .desc("TitusMaster port number (default 7001)")
                .build());
        options.addOption(Option.builder("S").longOpt("simulator-host").argName("simulator_host_name").hasArg().required(false)
                .desc("Cloud simulator host name")
                .build());
        options.addOption(Option.builder("r").longOpt("simulator-port").argName("simulator_port_number").hasArg().required(false)
                .type(Number.class)
                .desc("Cloud simulator port number")
                .build());
        return options;
    }

    private static CommandLine parseOptions(String[] args) {
        Options options = getOptions();

        CommandLineParser parser = new DefaultParser();
        try {
            return parser.parse(options, args);
        } catch (ParseException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    private static boolean hasHelpOption(String[] args) {
        for (String arg : args) {
            if (arg.equals("-h") || arg.equals("--help")) {
                return true;
            }
        }
        return false;
    }

    private static void printHelp() {
        PrintWriter writer = new PrintWriter(System.out);
        HelpFormatter formatter = new HelpFormatter();

        writer.println("Usage: LoadGenerator [params]");
        writer.println();
        writer.println("Options");
        formatter.printOptions(writer, 128, getOptions(), 4, 4);
        writer.println();
        writer.flush();
    }

    public static void main(String[] args) {
        if (hasHelpOption(args)) {
            printHelp();
            return;
        }
        LoadGenerator perf = null;
        try {
            perf = new LoadGenerator(args);
        } catch (Throwable e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(-1);
        }

        try {
            perf.run();
        } catch (Exception e) {
            System.err.println("ERROR: test run failure");
            e.printStackTrace();
        } finally {
            perf.tearDown();
        }
    }
}
