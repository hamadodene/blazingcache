/*
 Licensed to Diennea S.r.l. under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. Diennea S.r.l. licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.

 */
package blazingcache.services;

import blazingcache.network.ServerHostData;
import blazingcache.network.netty.NettyChannelAcceptor;
import blazingcache.server.CacheServer;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Created by enrico.olivelliF.
 */
public class ServerMain implements AutoCloseable {

    private CacheServer cacheServer;
    private final Properties configuration;
    private final PidFileLocker pidFileLocker;

    private static ServerMain runningInstance;

    public CacheServer getServer() {
        return cacheServer;
    }

    public ServerMain(Properties configuration) {
        this.configuration = configuration;
        this.pidFileLocker = new PidFileLocker(Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath());
    }

    @Override
    public void close() {

        if (cacheServer != null) {
            try {
                cacheServer.close();
            } catch (Exception ex) {
                Logger.getLogger(ServerMain.class.getName()).log(Level.SEVERE, null, ex);
            } finally {
                cacheServer = null;
            }
        }
        pidFileLocker.close();
        running.countDown();
    }

    public static void main(String... args) {
        try {

            Properties configuration = new Properties();
            File configFile;
            if (args.length > 0) {
                configFile = new File(args[0]);
                try (FileReader reader = new FileReader(configFile)) {
                    configuration.load(reader);
                }
            } else {
                configFile = new File("conf/server.properties");
                if (configFile.isFile()) {
                    try (FileReader reader = new FileReader(configFile)) {
                        configuration.load(reader);
                    }
                } else {
                    throw new Exception("Cannot find " + configFile.getAbsolutePath());
                }
            }

            Runtime.getRuntime().addShutdownHook(new Thread("ctrlc-hook") {

                @Override
                public void run() {
                    System.out.println("Ctrl-C trapped. Shutting down");
                    ServerMain _brokerMain = runningInstance;
                    if (_brokerMain != null) {
                        _brokerMain.close();
                    }
                }

            });
            runningInstance = new ServerMain(configuration);
            runningInstance.start();
            runningInstance.join();

        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    private final static CountDownLatch running = new CountDownLatch(1);

    public void join() {
        try {
            running.await();
        } catch (InterruptedException discard) {
        }
    }

    public void start() throws Exception {
        pidFileLocker.lock();
        String host = configuration.getProperty("server.host", "127.0.0.1");
        int port = Integer.parseInt(configuration.getProperty("server.port", "1025"));
        boolean ssl = Boolean.parseBoolean(configuration.getProperty("server.ssl", "true"));
        String certfile = configuration.getProperty("server.ssl.certificatefile", "");
        String certchainfile = configuration.getProperty("server.ssl.certificatechainfile", "");
        String certpassword = configuration.getProperty("server.ssl.certificatefilepassword", null);
        String sslciphers = configuration.getProperty("server.ssl.ciphers", "");
        String sharedsecret = configuration.getProperty("sharedsecret", "blazing");
        String clusteringmode = configuration.getProperty("clustering.mode", "singleserver");
        int workerthreads = Integer.parseInt(configuration.getProperty("io.worker.threads", "16"));

        System.out.println("Starting BlazingCache Server");

        Map<String, String> additionalData = new HashMap<>();
        ServerHostData data = new ServerHostData(host, port, "", ssl, additionalData);
        cacheServer = new CacheServer(sharedsecret, data);

        switch (clusteringmode) {
            case "singleserver": {
                break;
            }
            case "clustered": {
                String zkAddress = configuration.getProperty("zk.address", "localhost:1281");
                int zkSessionTimeout = Integer.parseInt(configuration.getProperty("zk.sessiontimeout", "40000"));
                String zkPath = configuration.getProperty("zk.path", "/majordodo");
                cacheServer.setupCluster(zkAddress, zkSessionTimeout, zkPath, data);
                break;
            }
            default:
                throw new RuntimeException("bad value for clustering.mode property, only valid values are singleserver|clustered");
        }
//        broker.setExternalProcessChecker(() -> {
//            pidFileLocker.check();
//            return null;
//        });
        System.out.println("Listening for clients connections on " + host + ":" + port + " ssl=" + ssl);
        cacheServer.setWorkerThreads(workerthreads);
        //cacheServer.setupSsl(certificateFile, certpassword, certificateChain, sslCiphers);
        cacheServer.start();

//        server.setHost(host);
//        server.setPort(port);
//        server.setSsl(ssl);
//        if (!certfile.isEmpty()) {
//            server.setSslCertFile(new File(certfile));
//        }
//        if (!certchainfile.isEmpty()) {
//            server.setSslCertChainFile(new File(certchainfile));
//        }
//        if (certpassword != null) {
//            server.setSslCertPassword(certpassword);
//        }
//        if (sslciphers != null && !sslciphers.isEmpty()) {
//            server.setSslCiphers(Stream.of(sslciphers.split(",")).map(s -> s.trim()).filter(s -> !s.isEmpty()).collect(Collectors.toList()));
//        }
//        server.start();

        System.out.println("Server starter");
    }

    public void waitForLeadership() throws Exception {
        for (int i = 0; i < 100; i++) {
            System.out.println("Waiting for leadership");
            if (cacheServer.isLeader()) {
                return;
            }
            Thread.sleep(1000);
        }
    }

}
