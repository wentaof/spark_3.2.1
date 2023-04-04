/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hive.service.server;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.List;

import org.apache.curator.framework.CuratorFramework;


import org.apache.hadoop.hive.ql.util.ZooKeeperHiveHelper;
import org.apache.hadoop.hive.shims.Utils;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.shaded.com.google.common.base.Joiner;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.ACLProvider;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.framework.recipes.nodes.PersistentEphemeralNode;
import org.apache.hive.common.util.HiveVersionInfo;
import org.apache.zookeeper.*;
import scala.runtime.AbstractFunction0;
import scala.runtime.BoxedUnit;

import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.hadoop.hive.common.JvmPauseMonitor;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.conf.HiveConf.ConfVars;
import org.apache.hive.common.util.HiveStringUtils;
import org.apache.hive.service.CompositeService;
import org.apache.hive.service.cli.CLIService;
import org.apache.hive.service.cli.thrift.ThriftBinaryCLIService;
import org.apache.hive.service.cli.thrift.ThriftCLIService;
import org.apache.hive.service.cli.thrift.ThriftHttpCLIService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.spark.util.ShutdownHookManager;
import org.apache.zookeeper.data.ACL;
/**
 * HiveServer2.
 *
 */
public class HiveServer2 extends CompositeService {
  private static final Logger LOG = LoggerFactory.getLogger(HiveServer2.class);

  private CLIService cliService;
  private ThriftCLIService thriftCLIService;

  private CuratorFramework zooKeeperClient;
  private boolean deregisteredWithZooKeeper = false;
  private PersistentEphemeralNode znode;
  private String znodePath;

  public HiveServer2() {
    super(HiveServer2.class.getSimpleName());
    HiveConf.setLoadHiveServer2Config(true);
  }

  @Override
  public synchronized void init(HiveConf hiveConf) {
    cliService = new CLIService(this);
    addService(cliService);
    if (isHTTPTransportMode(hiveConf)) {
      thriftCLIService = new ThriftHttpCLIService(cliService);
    } else {
      thriftCLIService = new ThriftBinaryCLIService(cliService);
    }
    addService(thriftCLIService);
    super.init(hiveConf);

    // Add a shutdown hook for catching SIGTERM & SIGINT
    // this must be higher than the Hadoop Filesystem priority of 10,
    // which the default priority is.
    // The signature of the callback must match that of a scala () -> Unit
    // function
    ShutdownHookManager.addShutdownHook(
        new AbstractFunction0<BoxedUnit>() {
          public BoxedUnit apply() {
            try {
              LOG.info("Hive Server Shutdown hook invoked");
              stop();
            } catch (Throwable e) {
              LOG.warn("Ignoring Exception while stopping Hive Server from shutdown hook",
                  e);
            }
            return BoxedUnit.UNIT;
          }
        });
  }

  public static boolean isHTTPTransportMode(HiveConf hiveConf) {
    String transportMode = System.getenv("HIVE_SERVER2_TRANSPORT_MODE");
    if (transportMode == null) {
      transportMode = hiveConf.getVar(HiveConf.ConfVars.HIVE_SERVER2_TRANSPORT_MODE);
    }
    if (transportMode != null && (transportMode.equalsIgnoreCase("http"))) {
      return true;
    }
    return false;
  }

  @Override
  public synchronized void start() {
    super.start();
  }

  @Override
  public synchronized void stop() {
    LOG.info("Shutting down HiveServer2");
    super.stop();
  }

  private static void startHiveServer2() throws Throwable {
    long attempts = 0, maxAttempts = 1;
    while (true) {
      LOG.info("Starting HiveServer2");
      HiveConf hiveConf = new HiveConf();
      maxAttempts = hiveConf.getLongVar(HiveConf.ConfVars.HIVE_SERVER2_MAX_START_ATTEMPTS);
      HiveServer2 server = null;
      try {
        server = new HiveServer2();
        server.init(hiveConf);
        server.start();
        try {
          JvmPauseMonitor pauseMonitor = new JvmPauseMonitor(hiveConf);
          pauseMonitor.start();
        } catch (Throwable t) {
          LOG.warn("Could not initiate the JvmPauseMonitor thread.", t);
        }
        break;
      } catch (Throwable throwable) {
        if (server != null) {
          try {
            server.stop();
          } catch (Throwable t) {
            LOG.info("Exception caught when calling stop of HiveServer2 before retrying start", t);
          } finally {
            server = null;
          }
        }
        if (++attempts >= maxAttempts) {
          throw new Error("Max start attempts " + maxAttempts + " exhausted", throwable);
        } else {
          LOG.warn("Error starting HiveServer2 on attempt " + attempts
              + ", will retry in 60 seconds", throwable);
          try {
            Thread.sleep(60L * 1000L);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
      }
    }
  }

  public static void main(String[] args) {
    HiveConf.setLoadHiveServer2Config(true);
    ServerOptionsProcessor oproc = new ServerOptionsProcessor("hiveserver2");
    ServerOptionsProcessorResponse oprocResponse = oproc.parse(args);

    HiveStringUtils.startupShutdownMessage(HiveServer2.class, args, LOG);

    // Call the executor which will execute the appropriate command based on the parsed options
    oprocResponse.getServerOptionsExecutor().execute();
  }

  /**
   * ServerOptionsProcessor.
   * Process arguments given to HiveServer2 (-hiveconf property=value)
   * Set properties in System properties
   * Create an appropriate response object,
   * which has executor to execute the appropriate command based on the parsed options.
   */
  public static class ServerOptionsProcessor {
    private final Options options = new Options();
    private org.apache.commons.cli.CommandLine commandLine;
    private final String serverName;
    private final StringBuilder debugMessage = new StringBuilder();

    @SuppressWarnings("static-access")
    public ServerOptionsProcessor(String serverName) {
      this.serverName = serverName;
      // -hiveconf x=y
      options.addOption(OptionBuilder
          .withValueSeparator()
          .hasArgs(2)
          .withArgName("property=value")
          .withLongOpt("hiveconf")
          .withDescription("Use value for given property")
          .create());
      options.addOption(new Option("H", "help", false, "Print help information"));
    }

    public ServerOptionsProcessorResponse parse(String[] argv) {
      try {
        commandLine = new GnuParser().parse(options, argv);
        // Process --hiveconf
        // Get hiveconf param values and set the System property values
        Properties confProps = commandLine.getOptionProperties("hiveconf");
        for (String propKey : confProps.stringPropertyNames()) {
          // save logging message for log4j output latter after log4j initialize properly
          debugMessage.append("Setting " + propKey + "=" + confProps.getProperty(propKey) + ";\n");
          System.setProperty(propKey, confProps.getProperty(propKey));
        }

        // Process --help
        if (commandLine.hasOption('H')) {
          return new ServerOptionsProcessorResponse(new HelpOptionExecutor(serverName, options));
        }
      } catch (ParseException e) {
        // Error out & exit - we were not able to parse the args successfully
        System.err.println("Error starting HiveServer2 with given arguments: ");
        System.err.println(e.getMessage());
        System.exit(-1);
      }
      // Default executor, when no option is specified
      return new ServerOptionsProcessorResponse(new StartOptionExecutor());
    }

    StringBuilder getDebugMessage() {
      return debugMessage;
    }
  }

  /**
   * The response sent back from {@link ServerOptionsProcessor#parse(String[])}
   */
  static class ServerOptionsProcessorResponse {
    private final ServerOptionsExecutor serverOptionsExecutor;

    ServerOptionsProcessorResponse(ServerOptionsExecutor serverOptionsExecutor) {
      this.serverOptionsExecutor = serverOptionsExecutor;
    }

    ServerOptionsExecutor getServerOptionsExecutor() {
      return serverOptionsExecutor;
    }
  }

  /**
   * The executor interface for running the appropriate HiveServer2 command based on parsed options
   */
  interface ServerOptionsExecutor {
    void execute();
  }

  /**
   * HelpOptionExecutor: executes the --help option by printing out the usage
   */
  static class HelpOptionExecutor implements ServerOptionsExecutor {
    private final Options options;
    private final String serverName;

    HelpOptionExecutor(String serverName, Options options) {
      this.options = options;
      this.serverName = serverName;
    }

    @Override
    public void execute() {
      new HelpFormatter().printHelp(serverName, options);
      System.exit(0);
    }
  }

  /**
   * StartOptionExecutor: starts HiveServer2.
   * This is the default executor, when no option is specified.
   */
  static class StartOptionExecutor implements ServerOptionsExecutor {
    @Override
    public void execute() {
      try {
        startHiveServer2();
      } catch (Throwable t) {
        LOG.error("Error starting HiveServer2", t);
        System.exit(-1);
      }
    }
  }
  private String getServerInstanceURI() throws Exception {
    if ((thriftCLIService == null) || (thriftCLIService.getServerIPAddress() == null)) {
      throw new Exception("Unable to get the server address; it hasn't been initialized yet.");
    }
    return thriftCLIService.getServerIPAddress().getHostName() + ":"
            + thriftCLIService.getPortNumber();
  }
  /**
   * For a kerberized cluster, we dynamically set up the client's JAAS conf.
   *
   * @param hiveConf
   * @return
   * @throws Exception
   */
  private void setUpZooKeeperAuth(HiveConf hiveConf) throws Exception {
    if (UserGroupInformation.isSecurityEnabled()) {
      String principal = hiveConf.getVar(ConfVars.HIVE_SERVER2_KERBEROS_PRINCIPAL);
      if (principal.isEmpty()) {
        throw new IOException("HiveServer2 Kerberos principal is empty");
      }
      String keyTabFile = hiveConf.getVar(ConfVars.HIVE_SERVER2_KERBEROS_KEYTAB);
      if (keyTabFile.isEmpty()) {
        throw new IOException("HiveServer2 Kerberos keytab is empty");
      }
      // Install the JAAS Configuration for the runtime
      Utils.setZookeeperClientKerberosJaasConfig(principal, keyTabFile);
    }
  }
  /**
   * ACLProvider for providing appropriate ACLs to CuratorFrameworkFactory
   */
  private final ACLProvider zooKeeperAclProvider = new ACLProvider() {

    @Override
    public List<ACL> getDefaultAcl() {
      List<ACL> nodeAcls = new ArrayList<ACL>();
      if (UserGroupInformation.isSecurityEnabled()) {
        // Read all to the world
        nodeAcls.addAll(ZooDefs.Ids.READ_ACL_UNSAFE);
        // Create/Delete/Write/Admin to the authenticated user
        nodeAcls.add(new ACL(ZooDefs.Perms.ALL, ZooDefs.Ids.AUTH_IDS));
      } else {
        // ACLs for znodes on a non-kerberized cluster
        // Create/Read/Delete/Write/Admin to the world
        nodeAcls.addAll(ZooDefs.Ids.OPEN_ACL_UNSAFE);
      }
      return nodeAcls;
    }

    @Override
    public List<ACL> getAclForPath(String path) {
      return getDefaultAcl();
    }
  };
  /**
   * Adds a server instance to ZooKeeper as a znode.
   *
   * @param hiveConf
   * @throws Exception
   */
  private void addServerInstanceToZooKeeper(HiveConf hiveConf) throws Exception {
    String zooKeeperEnsemble = ZooKeeperHiveHelper.getQuorumServers(hiveConf);
    String rootNamespace = hiveConf.getVar(HiveConf.ConfVars.HIVE_SERVER2_ZOOKEEPER_NAMESPACE);
    String instanceURI = getServerInstanceURI();
    setUpZooKeeperAuth(hiveConf);
    int sessionTimeout =
            (int) hiveConf.getTimeVar(HiveConf.ConfVars.HIVE_ZOOKEEPER_SESSION_TIMEOUT,
                    TimeUnit.MILLISECONDS);
    int baseSleepTime =
            (int) hiveConf.getTimeVar(HiveConf.ConfVars.HIVE_ZOOKEEPER_CONNECTION_BASESLEEPTIME,
                    TimeUnit.MILLISECONDS);
    int maxRetries = hiveConf.getIntVar(HiveConf.ConfVars.HIVE_ZOOKEEPER_CONNECTION_MAX_RETRIES);
    // Create a CuratorFramework instance to be used as the ZooKeeper client
    // Use the zooKeeperAclProvider to create appropriate ACLs
    zooKeeperClient =
            CuratorFrameworkFactory.builder().connectString(zooKeeperEnsemble)
                    .sessionTimeoutMs(sessionTimeout).aclProvider(zooKeeperAclProvider)
                    .retryPolicy(new ExponentialBackoffRetry(baseSleepTime, maxRetries)).build();
    zooKeeperClient.start();
    // Create the parent znodes recursively; ignore if the parent already exists.
    try {
      zooKeeperClient.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT)
              .forPath(ZooKeeperHiveHelper.ZOOKEEPER_PATH_SEPARATOR + rootNamespace);
      LOG.info("Created the root name space: " + rootNamespace + " on ZooKeeper for HiveServer2");
    } catch (KeeperException e) {
      if (e.code() != KeeperException.Code.NODEEXISTS) {
        LOG.error("Unable to create HiveServer2 namespace: " + rootNamespace + " on ZooKeeper", e);
        throw e;
      }
    }
    // Create a znode under the rootNamespace parent for this instance of the server
    // Znode name: serverUri=host:port;version=versionInfo;sequence=sequenceNumber
    try {
      String pathPrefix =
              ZooKeeperHiveHelper.ZOOKEEPER_PATH_SEPARATOR + rootNamespace
                      + ZooKeeperHiveHelper.ZOOKEEPER_PATH_SEPARATOR + "serverUri=" + instanceURI + ";"
                      + "version=" + HiveVersionInfo.getVersion() + ";" + "sequence=";
      String znodeData = "";
      if (hiveConf.getBoolVar(HiveConf.ConfVars.HIVE_SERVER2_ZOOKEEPER_PUBLISH_CONFIGS)) {
        // HiveServer2 configs that this instance will publish to ZooKeeper,
        // so that the clients can read these and configure themselves properly.
        Map<String, String> confsToPublish = new HashMap<String, String>();
        addConfsToPublish(hiveConf, confsToPublish);
        // Publish configs for this instance as the data on the node
        znodeData = Joiner.on(';').withKeyValueSeparator("=").join(confsToPublish);
      } else {
        znodeData = instanceURI;
      }
      byte[] znodeDataUTF8 = znodeData.getBytes(Charset.forName("UTF-8"));
      znode =
              new PersistentEphemeralNode(zooKeeperClient,
                      PersistentEphemeralNode.Mode.EPHEMERAL_SEQUENTIAL, pathPrefix, znodeDataUTF8);

      znode.start();
      // We'll wait for 120s for node creation
      long znodeCreationTimeout = 120;
      if (!znode.waitForInitialCreate(znodeCreationTimeout, TimeUnit.SECONDS)) {
        throw new Exception("Max znode creation wait time: " + znodeCreationTimeout + "s exhausted");
      }
      setDeregisteredWithZooKeeper(false);
      znodePath = znode.getActualPath();
      // Set a watch on the znode
      if (zooKeeperClient.checkExists().usingWatcher(new DeRegisterWatcher()).forPath(znodePath) == null) {
        // No node exists, throw exception
        throw new Exception("Unable to create znode for this HiveServer2 instance on ZooKeeper.");
      }
      LOG.info("Created a znode on ZooKeeper for HiveServer2 uri: " + instanceURI);
    } catch (Exception e) {
      LOG.error("Unable to create a znode for this server instance", e);
      if (znode != null) {
        znode.close();
      }
      throw (e);
    }
  }
  /**
   * The watcher class which sets the de-register flag when the znode corresponding to this server
   * instance is deleted. Additionally, it shuts down the server if there are no more active client
   * sessions at the time of receiving a 'NodeDeleted' notification from ZooKeeper.
   */
  private class DeRegisterWatcher implements Watcher {
    @Override
    public void process(WatchedEvent event) {
      if (event.getType().equals(Watcher.Event.EventType.NodeDeleted)) {
        if (znode != null) {
          try {
            znode.close();
            LOG.warn("This HiveServer2 instance is now de-registered from ZooKeeper. "
                    + "The server will be shut down after the last client sesssion completes.");
          } catch (IOException e) {
            LOG.error("Failed to close the persistent ephemeral znode", e);
          } finally {
            HiveServer2.this.setDeregisteredWithZooKeeper(true);
            // If there are no more active client sessions, stop the server
            if (cliService.getSessionManager().getOpenSessionCount() == 0) {
              LOG.warn("This instance of HiveServer2 has been removed from the list of server "
                      + "instances available for dynamic service discovery. "
                      + "The last client session has ended - will shutdown now.");
              HiveServer2.this.stop();
            }
          }
        }
      }
    }
  }

  private void removeServerInstanceFromZooKeeper() throws Exception {
    setDeregisteredWithZooKeeper(true);

    if (znode != null) {
      znode.close();
    }
    zooKeeperClient.close();
    LOG.info("Server instance removed from ZooKeeper.");
  }
  private void setDeregisteredWithZooKeeper(boolean deregisteredWithZooKeeper) {
    this.deregisteredWithZooKeeper = deregisteredWithZooKeeper;
  }
  public String getServerHost() throws Exception {
    if ((thriftCLIService == null) || (thriftCLIService.getServerIPAddress() == null)) {
      throw new Exception("Unable to get the server address; it hasn't been initialized yet.");
    }
    return thriftCLIService.getServerIPAddress().getHostName();
  }
  /**
   * Add conf keys, values that HiveServer2 will publish to ZooKeeper.
   * @param hiveConf
   */
  private void addConfsToPublish(HiveConf hiveConf, Map<String, String> confsToPublish) {
    // Hostname
    confsToPublish.put(ConfVars.HIVE_SERVER2_THRIFT_BIND_HOST.varname,
            hiveConf.getVar(ConfVars.HIVE_SERVER2_THRIFT_BIND_HOST));
    // Transport mode
    confsToPublish.put(ConfVars.HIVE_SERVER2_TRANSPORT_MODE.varname,
            hiveConf.getVar(ConfVars.HIVE_SERVER2_TRANSPORT_MODE));
    // Transport specific confs
    if (isHTTPTransportMode(hiveConf)) {
      confsToPublish.put(ConfVars.HIVE_SERVER2_THRIFT_HTTP_PORT.varname,
              Integer.toString(hiveConf.getIntVar(ConfVars.HIVE_SERVER2_THRIFT_HTTP_PORT)));
      confsToPublish.put(ConfVars.HIVE_SERVER2_THRIFT_HTTP_PATH.varname,
              hiveConf.getVar(ConfVars.HIVE_SERVER2_THRIFT_HTTP_PATH));
    } else {
      confsToPublish.put(ConfVars.HIVE_SERVER2_THRIFT_PORT.varname,
              Integer.toString(hiveConf.getIntVar(ConfVars.HIVE_SERVER2_THRIFT_PORT)));
      confsToPublish.put(ConfVars.HIVE_SERVER2_THRIFT_SASL_QOP.varname,
              hiveConf.getVar(ConfVars.HIVE_SERVER2_THRIFT_SASL_QOP));
    }
    // Auth specific confs
    confsToPublish.put(ConfVars.HIVE_SERVER2_AUTHENTICATION.varname,
            hiveConf.getVar(ConfVars.HIVE_SERVER2_AUTHENTICATION));
    if (isKerberosAuthMode(hiveConf)) {
      confsToPublish.put(ConfVars.HIVE_SERVER2_KERBEROS_PRINCIPAL.varname,
              hiveConf.getVar(ConfVars.HIVE_SERVER2_KERBEROS_PRINCIPAL));
    }
    // SSL conf
    confsToPublish.put(ConfVars.HIVE_SERVER2_USE_SSL.varname,
            Boolean.toString(hiveConf.getBoolVar(ConfVars.HIVE_SERVER2_USE_SSL)));
  }
  public static boolean isKerberosAuthMode(HiveConf hiveConf) {
    String authMode = hiveConf.getVar(HiveConf.ConfVars.HIVE_SERVER2_AUTHENTICATION);
    if (authMode != null && (authMode.equalsIgnoreCase("KERBEROS"))) {
      return true;
    }
    return false;
  }
}
