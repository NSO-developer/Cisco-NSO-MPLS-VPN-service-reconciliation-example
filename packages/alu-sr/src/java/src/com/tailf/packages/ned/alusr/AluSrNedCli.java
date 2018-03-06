package com.tailf.packages.ned.alusr;

import static com.tailf.packages.ned.alusr.Util.append;
import static com.tailf.packages.ned.alusr.Util.drop;
import static com.tailf.packages.ned.alusr.Util.join;
import static com.tailf.packages.ned.alusr.Util.lines;
import static com.tailf.packages.ned.alusr.Util.newList;
import static com.tailf.packages.ned.alusr.Util.take;
import static java.lang.String.format;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.util.List;
import java.util.Stack;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;

import com.tailf.conf.Conf;
import com.tailf.conf.ConfBuf;
import com.tailf.conf.ConfException;
import com.tailf.conf.ConfObject;
import com.tailf.conf.ConfPath;
import com.tailf.conf.ConfUInt16;
import com.tailf.conf.ConfValue;
import com.tailf.conf.ConfXMLParam;
import com.tailf.conf.ConfXMLParamValue;
import com.tailf.maapi.Maapi;
import com.tailf.maapi.MaapiCrypto;
import com.tailf.ncs.ResourceManager;
import com.tailf.ncs.annotations.Resource;
import com.tailf.ncs.annotations.ResourceType;
import com.tailf.ncs.annotations.Scope;
import com.tailf.ned.NedCapability;
import com.tailf.ned.NedCliBase;
import com.tailf.ned.NedCliBaseTemplate;
import com.tailf.ned.NedCmd;
import com.tailf.ned.NedErrorCode;
import com.tailf.ned.NedException;
import com.tailf.ned.NedExpectResult;
import com.tailf.ned.NedMux;
import com.tailf.ned.NedWorker;
import com.tailf.ned.NedWorker.TransactionIdMode;
import com.tailf.ned.SSHSessionException;

/**
 * Implements the alu-sr CLI NED
 * @author jrendel
 *
 */
public class AluSrNedCli extends NedCliBaseTemplate {

    private static final String IDENTITY = "http://tail-f.com/ned/alu-sr";
    private static final String MODULE   = "alu-sr";
    private static final String DATE     = "2015-06-11";
    private static final String VERSION  = "3.4";

    private static Logger LOGGER  = Logger.getLogger(AluSrNedCli.class);
    private static String prompt = "\\S+[#\\$] ";
    private static Pattern prompt_pattern = Pattern.compile(prompt);

    private NedCapability CAPAS[] = new NedCapability[2];
    private NedCapability STATS_CAPAS[] = new NedCapability[0];

    private boolean doAdminSaveInPersist   = true;
    private boolean useRollbackForTransId  = false;
    private boolean useTransactionalConfig = false;
    private boolean doShutdownBeforeApply  = false;

    private enum dt { DEVICE, NETSIM };
    private dt devicetype = dt.DEVICE;

    @Resource(type=ResourceType.MAAPI, scope=Scope.INSTANCE)
    public  Maapi  mm;

    private final static ConfigCleaner configCleaner = new ConfigCleaner();

    /**
     * Inner class used for storing the device info in CDB.
     * Is executed in a separate thread.
     * @author jrendel
     *
     */
    private class StoreDeviceInfo implements Runnable {
        private String os;
        private String machine;

        public StoreDeviceInfo(String os, String machine) {
            this.os = os;
            this.machine = machine;
        }

        @Override
        public void run() {
            try {
                int tid = mm.startTrans(Conf.DB_RUNNING, Conf.MODE_READ_WRITE);

                String platformPath =
                    "/ncs:devices/device{"+device_id+"}/platform";

                if (!mm.exists(tid, platformPath)) {
                    mm.create(tid, platformPath);
                }

                mm.setElem(tid, os, platformPath + "/os-version");
                mm.setElem(tid, machine, platformPath + "/machine");
                mm.applyTrans(tid, false);
                mm.finishTrans(tid);

                LOGGER.debug("Finished writing device info to CDB");

            } catch (Exception e) {
               LOGGER.error("Failed to write device info into CDB :: "
                            + e.getMessage());
            }
        }
    }

    /**
     * NED alu-sr constructor
     */
    public AluSrNedCli() {
        super();
        try {
            ResourceManager.registerResources(this);
        } catch (Exception e) {
            LOGGER.error("Error injecting Resources", e);
        }
    }

    /**
     * NED alu-sr contructor
     * @param device_id       - configured device name
     * @param ip              - ip address to device
     * @param port            - configured port to device
     * @param proto           - protocol (either ssh or telnet)
     * @param ruser           - remote user id
     * @param pass            - remote password
     * @param secpass         - secondary password (not used)
     * @param trace           - trace mode enabled/disabled
     * @param connectTimeout  - connect timeout (msec)
     * @param readTimeout     - read timeout (msec)
     * @param writeTimeout    - write timeout (msec)
     * @param mux             -
     * @param worker          - worker context
     */
    public AluSrNedCli(String device_id,
                       InetAddress ip,
                       int port,
                       String proto,
                       String ruser,
                       String pass,
                       String secpass,
                       boolean trace,
                       int connectTimeout,
                       int readTimeout,
                       int writeTimeout,
                       NedMux mux,
                       NedWorker worker) {

        super(device_id, ip, port, proto, ruser, pass, secpass,
              trace, connectTimeout, readTimeout, writeTimeout, mux,
              worker);

        Shutdown.load(device_id);
        int tid;
        this.CAPAS[0] = new NedCapability("", IDENTITY, MODULE, "", DATE, "");
        this.CAPAS[1] = new NedCapability("urn:ietf:params:netconf:"
            + "capability:with-defaults:1.0?"
            + "basic-mode=report-all",
            "urn:ietf:params:netconf:capability:with-defaults:1.0",
            "",
            "",
            "",
            "");

        if (trace)
            tracer = worker;
        else
            tracer = null;

        try {
            ResourceManager.registerResources(this);
        } catch (Exception e) {
            LOGGER.error("Error injecting Resources", e);
        }

        worker.setTimeout(connectTimeout);
        LOGGER.info("NED VERSION: alu-sr " + VERSION + " " + DATE);

        try {
            /*
             * Setup connection
             */
            if (proto.equals("ssh")) {
                setupSSH(worker);
            }
            else {
                setupTelnet(worker);
            }

        } catch (Exception e) {
            LOGGER.error("connect failed ",  e);
            worker.error(NedCmd.CONNECT_CLI, e.getMessage());
            return;
        }

        try {
            mm.setUserSession(1);
            tid = mm.startTrans(Conf.DB_RUNNING, Conf.MODE_READ);
            ConfValue v;
            String info;
            String[] paths =
            {
             "/ncs:devices/ncs:global-settings/ncs:ned-settings",
             "/ncs:devices/ncs:profiles/profile{" + MODULE
             + "}/ncs:ned-settings",
             "/ncs:devices/device{" + device_id + "}/ncs:ned-settings",
            };

            /*
             *  Get configuration for the alu-sr-admin-save-failed-behavior.
             *  Two alternatives:
             *  commit-transaction - (default)
             *      Do the admin-save operation in the persist hook.
             *  abort-transaction -
             *      Do the admin-save operation last in the commit hook.
             *
             *  This feature is configurable on global and device
             *  specific level.
             */
            for (String p: paths) {
                p += "/alu-sr-admin-save-failed-behaviour";
                if (mm.exists(tid, p)) {
                    v = mm.getElem(tid, p);

                    this.doAdminSaveInPersist =
                        ConfValue.getStringByValue(p, v).
                        equals("commit-transaction");
                }
            }

            info = this.doAdminSaveInPersist ? "persist" : "prepare";
            LOGGER.info("Admin-save configured to be excecuted in the "
                + info + " hook");

            /*
             *  Get configuration for the alu-sr-trans-id-method to use.
             *  Two alternatives:
             *  config-hash - (default)
             *      Dump the configuration and calculate a md5 hash
             *      of it. Calculate the transaction id out of it.
             *      This method always works, but can be slow with
             *      large configs.
             *  rollback-timestamp -
             *      Fetch the time stamp of the latest saved rollback
             *      checkpoint. Calculate the transaction id out of it.
             *      Much faster but does require that the system rollback
             *      feature is configured properly.
             */
            for (String p: paths) {
                p += "/alu-sr-transaction-id-method";
                if (mm.exists(tid, p)) {
                    v = mm.getElem(tid, p);

                    this.useRollbackForTransId =
                        ConfValue.getStringByValue(p, v).
                        equals("rollback-timestamp");
                }
            }

            info = this.useRollbackForTransId
                ? "rollback-timestamp" : "config-hash";
            LOGGER.info("Transaction ID method configured to use " + info);

            /*
             *  Get configuration for the alu-sr transactional-config option
             *  Two alternatives:
             *  false - (default)
             *      Apply config one-by-one in a non-transactional way.
             *  true -
             *      Use the candidate feature in the ALU box. This means
             *      all config is first applied to a candidate and then
             *      committed.
             *      The ALU device must have the candidate feature enabled
             *      for this to function properly.
             */
            for (String p: paths) {
                p += "/alu-sr-transactional-config";
                if (mm.exists(tid, p)) {
                    v = mm.getElem(tid, p);

                    this.useTransactionalConfig =
                        ConfValue.getStringByValue(p, v).equals("enabled");
                }
            }

            info = this.useTransactionalConfig ? "enabled" : "disabled";
            LOGGER.info("Transactional configuration is " + info);

            /*
             *  Get configuration for the alu-sr do-shutdown-before-apply option
             *  Two alternatives:
             *  false - (default)
             *      Apply config the standard way.
             *  true -
             *      If config object is in 'no shutdown' state, do first
             *      pull it to 'shutdown' before altering any config.
             *      Restore the state afterwards.
             */
            for (String p: paths) {
                p += "/alu-sr-do-shutdown-before-apply-config";
                if (mm.exists(tid, p)) {
                    v = mm.getElem(tid, p);

                    this.doShutdownBeforeApply =
                        ConfValue.getStringByValue(p, v).equals("enabled");
                }
            }

            info = this.doShutdownBeforeApply ? "enabled" : "disabled";
            LOGGER.info("Do shutdown before apply configuration is " + info);


            /*
             * Get optional proxy configuration
             * This is used when the ALU device is connected behind
             * for instance a terminal server.
             */
            String devpath = "/ncs:devices/device{"
                            + device_id
                            + "}/ned-settings/alu-sr-proxy-settings";

            if (mm.exists(tid, devpath)) {
                LOGGER.info("Connected to proxy : " + ip.toString());

                String p = devpath + "/remote-connection";
                v = mm.getElem(tid, p);
                String remoteConnection = ConfValue.getStringByValue(p, v);

                p = devpath + "/remote-user";
                v = mm.getElem(tid, p);
                String remoteUser = ConfValue.getStringByValue(p, v);

                p = devpath + "/remote-password";
                v = mm.getElem(tid, p);
                mm.finishTrans(tid);

                MaapiCrypto mc = new MaapiCrypto(mm);
                String remotePass = mc.decrypt(v.toString());

                LOGGER.info("Connecting to device using " + remoteConnection);

                if (!remoteConnection.equals("serial")) {
                    /*
                     * SSH or Telnet proxy
                     */
                    p = devpath + "/remote-address";
                    v = mm.getElem(tid, p);
                    String remoteAddress = ConfValue.getStringByValue(p, v);

                    p = devpath + "/remote-port";
                    v = mm.getElem(tid, p);
                    int remotePort = (int) ((ConfUInt16) v).longValue();

                    p = devpath + "/proxy-prompt";
                    v = mm.getElem(tid, p);
                    String proxyPrompt = ConfValue.getStringByValue(p, v);

                    /*
                     * Setup connection between proxy and device.
                     */
                    try {
                        session.expect(proxyPrompt);
                        if (remoteConnection.equals("ssh")) {
                            session.println("ssh -p "
                                +remotePort
                                +" "
                                +remoteUser
                                +"@"
                                +remoteAddress);
                        } else {
                            session.println("telnet "
                                +remoteAddress
                                +" "
                                +remotePort);
                            session.expect("[Ll]ogin:");
                        }

                        session.expect("[Pp]assword:");
                        session.println(remotePass);
                    }
                    catch (Exception e) {
                        LOGGER.error("connect from proxy failed ",  e);
                        worker.
                        connectError(NedErrorCode.CONNECT_CONNECTION_REFUSED,
                                     e.getMessage());
                        return;
                    }

                } else {
                    /*
                     * Proxy is a terminal server, typically using
                     * a serial connection to the device.
                     *
                     * When connecting to the device it might require a
                     * login procedure. However, if the last login session
                     * is still active you will get a prompt instead.
                     */
                    try {
                        session.println("");
                        NedExpectResult res =
                            session.expect(new String[] {"[Ll]ogin:",
                                                         prompt});
                        if (res.getHit() == 0) {
                            session.println(remoteUser);
                            session.expect("[Pp]assword:");
                            session.println(remotePass);
                        }

                        /*
                         * Make sure to be on top mode.
                         */
                        session.println("exit all");
                        session.expect("exit all");
                    }
                    catch (Exception e) {
                        LOGGER.error("serial connect/login failed ",  e);
                        worker.
                        connectError(NedErrorCode.CONNECT_CONNECTION_REFUSED,
                                     e.getMessage());
                        return;
                    }
                }
            }
        }
        catch (Exception e) {
            LOGGER.error("setup failed ",  e);
            worker.error(NedCmd.CONNECT_CLI, e.getMessage());
            return;
        }

        try {
            session.expect(new String[] {".*#", prompt});

            session.println("environment no more");
            session.expect(prompt_pattern);

            session.println("show version");
            String version = session.expect(prompt_pattern);

            // look for version string
            if (version.indexOf("ALCATEL SR 7750 ") >= 0 ||
                version.indexOf("7705") >= 0 ||
                version.indexOf("7950") >= 0 ||
                version.indexOf("7450") >= 0 ||
                version.indexOf("7210") >= 0) {
                String os;
                String machine;

                if (version.indexOf("NETSIM") >= 0) {
                    devicetype = dt.NETSIM;
                    os = "NETSIM";
                    machine = "NETSIM";
                } else {
                    devicetype = dt.DEVICE;

                    /*
                     * Extract detailed os version info
                     */
                    session.println("show system information |" +
                        " match \"System Version\"");
                    os = session.expect(prompt_pattern);
                    if (os.contains(":")) {
                        os = os.split(":")[1].trim();
                    }
                    else {
                        os = "UNKNOWN";
                    }
                        session.println("show system information |" +
                        " match \"System Type\"");

                    /*
                     * Extract detailed device model
                     */
                    machine = session.expect(prompt_pattern);

                    if (machine.contains(":")) {
                        machine = machine.split(":")[1].trim();
                    }
                    else {
                        machine = "UNKNOWN";
                    }
                }

                LOGGER.info("Found device type : " + machine);
                LOGGER.info("Found OS version  : " + os);

                /*
                 * Store in CDB
                 * Needs to be done in a separate context.
                 */
                //Thread t = new Thread(new StoreDeviceInfo(os, machine));
                //t.start();

                setConnectionData(CAPAS,
                                  STATS_CAPAS,
                                  true,  // want reverse-diff
                                  TransactionIdMode.UNIQUE_STRING);
            } else {
                session.close();
                worker.error(NedCmd.CONNECT_CLI, "unknown device");
            }

        }
        catch (Exception e) {
            worker.error(NedCmd.CONNECT_CLI, e.getMessage());
        }

    }

    @Override
    public void
    reconnect(NedWorker worker) {
        // all capas and transmode already set in constructor
        // nothing needs to be done
    }


    /**
     * Displays YANG modules covered by the class
     */
    @Override
    public String[] modules() {
        return new String[] { "alu-sr" };
    }

    /**
     * Display NED identity
     */
    @Override
    public String
    identity() {
        return "alu-sr-id:alu-sr";
    }

    /**
     * Move to top config context
     * @throws IOException
     * @throws SSHSessionException
     */
    private void
    moveToTopConfig() throws IOException, SSHSessionException {
        session.println("exit");
        session.expect(prompt_pattern);
    }

    /**
     * Send a line to the device and wait for response
     *
     * @param worker    - worker context
     * @param cmd       - NED command type
     * @param line      - command line to send
     * @param retrying  - number of retries
     *
     * @return true/false
     *
     * @throws NedException
     * @throws IOException
     * @throws SSHSessionException
     * @throws ApplyException
     */
    private boolean
    print_line_wait(NedWorker worker, int cmd, String line, int retrying)
        throws NedException, IOException, SSHSessionException, ApplyException {
        LOGGER.debug("Send line \":"+line+"\"");
        session.println(line);
        return noprint_line_wait(worker, cmd, line, retrying);
    }


    /**
     * Wait for response from the device.
     *
     * @param worker    - worker context
     * @param cmd       - NED command type
     * @param line      - command line
     * @param retrying  - number of retries
     * @return
     * @throws NedException
     * @throws IOException
     * @throws SSHSessionException
     * @throws ApplyException
     */
    private boolean
    noprint_line_wait(NedWorker worker, int cmd, String line, int retrying)
        throws NedException, IOException, SSHSessionException, ApplyException {
        String res = null;

        /*
         * wait only for the first word!!
         */
        expect(line.split(" ")[0]);
        res = expect(prompt_pattern);
        String lines[] = res.split("\n|\r");

        /*
         * first line contains remnant of input command. Skip it.
         */
        for(int i=1; i < lines.length ; i++) {
            if (lines[i].toLowerCase().indexOf("error") >= 0 ||
                lines[i].toLowerCase().indexOf("exceeded") >= 0 ||
                lines[i].toLowerCase().indexOf("invalid") >= 0 ||
                lines[i].toLowerCase().indexOf("incomplete") >= 0 ||
                lines[i].toLowerCase().indexOf("missing") >= 0 ||
                lines[i].toLowerCase().indexOf("duplicate name") >= 0 ||
                //lines[i].toLowerCase().indexOf("not 'shutdown'") >= 0 ||
                lines[i].toLowerCase().indexOf("not allowed") >= 0 ||
                lines[i].toLowerCase().indexOf("can not") >= 0 ||
                lines[i].toLowerCase().indexOf("failed") >= 0) {
                throw new ExtendedApplyException(line, lines[i], false, true);
            }
            if (lines[i].toLowerCase().indexOf("is in use") >= 0 ||
                lines[i].toLowerCase().indexOf("already exists") >= 0) {
                // wait a while and retry
                if (retrying > 60) {
                    // already tried enough, give up
                    throw new ExtendedApplyException(line, lines[i],
                                                     false, true);
                }
                else {
                    if (retrying == 0)
                        worker.setTimeout(10*60*1000);
                    // sleep a second
                    try { Thread.sleep(1*1000);
                    } catch (InterruptedException e) {
                        System.err.println("sleep interrupted");
                    }
                    return print_line_wait(worker, cmd, line, retrying+1);
                }
            }
        }

        return false;
    }


    /**
     * Expect utility for strings
     *
     * @param str - string to expect on
     *
     * @return output from session expect
     *
     * @throws IOException
     * @throws SSHSessionException
     */
    private String
    expect(String str) throws IOException, SSHSessionException {
        LOGGER.debug(format("Expecting \"%s\"", str));
        return session.expect(Pattern.quote(str));
    }


    /**
     * Expect utility for patterns
     *
     * @param pattern - pattern to expect on
     *
     * @return output from session expect
     *
     * @throws IOException
     * @throws SSHSessionException
     */
    private String
    expect(Pattern pattern)
        throws IOException, SSHSessionException {
        LOGGER.debug(format("Expecting \"%s\"", pattern));
        return session.expect(pattern);
    }


    /**
     * Utility for entering config mode.
     * Adapted for netsim, standard devices and
     * devices with transaction configuration enabled.
     *
     * @param worker - worker context
     * @param cmd    - NED command type
     * @param dry    - dry run mode or not
     *
     * @return true
     *
     * @throws NedException
     * @throws IOException
     * @throws SSHSessionException
     */
    private boolean
    enterConfig(NedWorker worker, int cmd, StringBuilder dry)
        throws NedException, IOException, SSHSessionException {

        String msgs[];
        String msgs_netsim[]    = {"configure"};
        String msgs_dev_std[]   = {"exit all", "configure"};
        String msgs_dev_trans[] = {"candidate edit exclusive",
                                   "exit all", "configure"};

        if (this.devicetype == dt.NETSIM) {
            msgs = msgs_netsim;
        }
        else if (this.useTransactionalConfig == true) {
            msgs = msgs_dev_trans;
        }
        else {
            msgs = msgs_dev_std;
        }

        for (String msg : msgs) {
            if (dry != null) {
                dry.append(msg+"\n");
                continue;
            }
            session.println(msg);
            session.expect(prompt_pattern);
        }
        return true;
    }


    /**
     * Utility for exiting configure mode
     *
     * @param dry  - dry run mode or not
     *
     * @throws IOException
     * @throws SSHSessionException
     */
    private void
    exitConfig(StringBuilder dry) throws IOException, SSHSessionException {

        String msg = "exit all";
        if (dry != null) {
            dry.append(msg);
            return;
        }

        if (devicetype == dt.DEVICE) {
            session.println(msg);
            session.expect(prompt_pattern);
        }
    }


    /**
     * Utility that returns true if cmd is a top command.
     * Top configuration commands contain no leading white spaces
     *
     * @param cmd - command
     *
     * @return {@code True} if cmd is a top configuration.
     */
    private boolean
    isTopStart(String cmd) {
        // assuming no trailing spaces...
        return (!cmd.equals("!") && (cmd.length() - cmd.trim().length()) == 0);
    }


    /**
     * Utility to execute "admin save" on the device.
     * This command saves running config to persistent memory on
     * the device
     *
     * @throws Exception
     */
    private void
    doAdminSave() throws Exception {

        if (devicetype == dt.DEVICE) {
            session.println("admin save");
            String res = session.expect(prompt_pattern);

            /*
             *  The output from this command looks like this
             *  admin save
             *  Writing configuration to <some file>.cfg
             *  Saving configuration ... OK
             *  Completed.
             *
             */
            // Check for keyword ok
            if (res.toLowerCase().indexOf("ok") < 0) {
                throw new Exception("admin save");
            }

            // Check for keyword completed.
            if (res.toLowerCase().indexOf("completed") < 0) {
                // Log a warning if not present.
                LOGGER.warn("admin save caused a warning :" +
                    res.substring(res.toLowerCase().indexOf("ok")));
            }

            if (this.useRollbackForTransId == true) {
                /*
                 *  CHECK-SYNC using rollback
                 *  The lines below are needed to save a rollback
                 *  checkpoint after a commit.
                 */
                session.println("admin rollback save");

                // Check for keywords ok and completed
                if (res.toLowerCase().indexOf("ok") < 0) {
                    throw new Exception("admin rollback save failed :: " + res);
                }
            }
        }
    }


    /**
     * Utility for dumping running config from the device
     *
     * @param worker - worker context
     *
     * @return string with the running config
     *
     * @throws Exception
     */
    private String
    get_config(NedWorker worker) throws Exception {
        session.println("admin display-config");
        session.expect("admin display-config", worker);

        String res = session.expect(prompt_pattern, worker);

        return configCleaner.cleanAll(res);
    }


    /**
     * Utility for dumping running debug config from the device.
     *
     * @param worker - worker context
     *
     * @return string with the running debug config
     * @throws Exception
     */
    private String
    get_debug(NedWorker worker) throws Exception {
        session.println("show debug");
        session.expect("show debug", worker);

        String res = session.expect(prompt_pattern, worker);

        return configCleaner.cleanAll(res);
    }


    /**
     * NED specific implementation of the ExtendedApplyException
     * @author jrendel
     *
     */
    @SuppressWarnings("serial")
    private class ExtendedApplyException extends ApplyException {
        public ExtendedApplyException(String line, String msg,
                                      boolean isAtTop,
                                      boolean inConfigMode) {
            super("command: "+line+": "+msg, isAtTop, inConfigMode);
        }
    }


    /**
     * Apply config to the device
     *
     * @param worker   - worker context
     * @param cmd      - NED command type
     * @param data     - config data to apply
     * @param dry      - dry run mode or not
     *
     * @throws NedException
     * @throws IOException
     * @throws SSHSessionException
     * @throws ApplyException
     */
    public void
    applyConfig(NedWorker worker, int cmd, String data, StringBuilder dry)
        throws NedException, IOException,
        SSHSessionException, ApplyException {
        boolean isAtTop=true;
        boolean isAtConfig=true;
        boolean explicitShutdownExecuted=false;
        boolean commitExecuted=false;

        if (!enterConfig(worker, cmd, dry))
            // we encountered an error
            return;

        LOGGER.debug("data before filter is: " + data);
        try {
            int th = worker.getFromTransactionId();
            List<String> outlines = newList();
            List<String> lines = lines(data);

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);

                // Prepare for right mode
                if (isTopStart(line) && devicetype == dt.DEVICE) {
                    if (line.equals("debug")) {
                        // Need to enter debug config mode.
                        isAtConfig=false;
                        outlines.add("exit all");
                    } else if (!isAtConfig) {
                        // Need to re-enter normal config mode.
                        isAtConfig=true;
                        outlines.add("exit all");
                        outlines.add("configure");
                    }
                }

                line = line.trim();

                if (devicetype == dt.DEVICE) {
                    // The index 0 is the default key for the ospf list.
                    // The device does not use a key for the default ospf entry
                    // so we need to strip the 0.
                    line = line.replaceAll("no ospf 0", "no ospf");
                    line = line.replaceAll("ospf 0( *)", "ospf$1");
                    line = line.replaceAll("^(egress-scheduler-override)",
                                           "$0 create");
                }

                line = line.replaceAll("used-by all", "");
                line = line.replaceAll("!$", "exit");


                //if (line.equals("commit")) {
                //    commitExecuted=true;
                //}
                //else if (line.equals("exit") && commitExecuted) {
                    /*
                     * Skip first exit after a commit has been executed
                     * This is a temporary workaround to avoid a bug
                     * in the NCS CLI engine.
                     */
                //    commitExecuted=false;
                //    continue;
                //}

                if (devicetype == dt.NETSIM && line.endsWith("create")) {
                    // Strip the create keywords when talking
                    // to a NETSIM device.
                    line = line.substring(0, line.lastIndexOf("create"));
                }

                if (line.isEmpty()) {
                    // Skip empty lines
                    continue;
                }

                // TODO: temporary fix to avoid bad recursive deletes -
                // clean this up later.
                if ( line.startsWith("ipv6 no dhcp6-relay") ) {
                    LOGGER.debug("discarding " + line);
                    continue;
                }

                // Ignore 'no' on persistent config containers
                if (line.startsWith("no dhcp") ||
                    line.startsWith("no stp") ||
                    line.startsWith("no ingress") ||
                    line.startsWith("no ethernet") ||
                    line.startsWith("no eth-cfm") ||
                    line.equals("no network") ||
                    line.equals("no begin") ||
                    line.equals("no interface-parameters") ||
                    line.equals("no interface system") ||
                    line.matches("^no egress$"))
                    {
                        LOGGER.debug("discarding " + line);
                        continue;
                    }

                // TODO: temporary fix to avoid deleting submode
                // recursive-delete does not work since it will
                // also delete container, sub option to recursive
                // delete needed.
                if ( line.startsWith("no single-sub-parameters") ) {
                    LOGGER.debug("discarding " + line);
                    continue;
                }

                if ( line.startsWith("router-advertisement no interface")) {
                    continue;
                }

                String shutdown = Shutdown.getShutdownCmd(line, lines, i);
                if (shutdown != null) {
                    outlines.add(shutdown);
                    outlines.add(line);
                }
                else if (doShutdownBeforeApply) {
                    /*
                     * NED is configured to do an automatic shutdown of
                     * objects before applying any config.
                     */
                    if (Shutdown.nodeHasShutdownLeaf(line, lines, i)) {
                        outlines.add(line);
                        if (!Shutdown.getCurrentState(mm, th, line, lines, i)) {
                            /*
                             * Node is currently in "no shutdown"
                             */
                            outlines.add("shutdown");
                        }
                    }
                    else {
                        if (line.equals("exit")) {
                            if (Shutdown.parentHasShutdownLeaf(lines, i)) {
                            /*
                             * Exiting a mode with a shutdown leaf.
                             */
                                if (!explicitShutdownExecuted &&
                                    !Shutdown.getCurrentState(mm, th, line,
                                                              lines, i)) {
                                    /*
                                     * Restore shutdown state.
                                     */
                                    outlines.add("no shutdown");
                                }
                            }

                            outlines.add(line);
                            explicitShutdownExecuted = false;
                        }
                        else if (line.contains("shutdown")) {
                            /*
                             * Applied config contains explicit (no) shutdown
                             * Overrides automatic restore of shutdown state
                             */
                            explicitShutdownExecuted = true;
                            outlines.add(line);
                        }
                        else {
                            outlines.add(line);
                        }
                    }
                }
                else {
                    /*
                     * NED is configured for NO automatic shutdown before
                     * apply.
                     */
                    outlines.add(line);
                }
            }

            do {
                List<String> chunk = take(100, outlines);
                outlines = drop(100, outlines);
                data = join(append("\n", chunk));

                LOGGER.debug("data after filter is: " + data);

                if (dry != null) {
                    dry.append(data);
                } else {
                    session.println(data);
                    long lastTime = System.currentTimeMillis();
                    long time;
                    for (String line: chunk) {
                        time = System.currentTimeMillis();
                        if ((time - lastTime) > (0.8 * readTimeout)) {
                            lastTime = time;
                            worker.setTimeout(readTimeout);
                        }
                        isAtTop = noprint_line_wait(worker, cmd, line, 0);
                    }
                }
            } while (!outlines.isEmpty());
        }
        catch (ApplyException e) {
            if (!e.isAtTop)
                moveToTopConfig();

            if (e.inConfigMode)
                exitConfig(dry);

            throw e;
        }

        // make sure we have exited from all submodes
        if (!isAtTop)
            moveToTopConfig();

        exitConfig(dry);
    }


    // mangle output, or just pass through we're invoked during prepare phase
    // of NCS
    @Override
    public void
    prepare(NedWorker worker, String data) throws Exception {

        if (trace)
            session.setTracer(worker);

        applyConfig(worker, NedCmd.PREPARE_CLI, data, null);

        if (this.useTransactionalConfig == true) {
            /*
             * Commit candidate and check result.
             */
            try {
                session.println("candidate commit");
                noprint_line_wait(worker, NedCmd.PREPARE_CLI,
                                  "candidate commit",0);
            }
            catch (Exception e) {
                throw new NedException(NedErrorCode.NED_EXTERNAL_ERROR,
                                       "Commit failed", e);
            }
        }

        if (doAdminSaveInPersist == false) {
            doAdminSave();
        }

        worker.prepareResponse();
    }


    @Override
    public void
    prepareDry(NedWorker worker, String data) throws Exception {
        StringBuilder dry = new StringBuilder();
        applyConfig(worker, NedCmd.PREPARE_CLI, data, dry);
        worker.prepareDryResponse(dry.toString());
    }


    // mangle output, we're invoked during prepare phase
    // of NCS
    @Override
    public void
    abort(NedWorker worker, String data) throws Exception {

        if (trace)
            session.setTracer(worker);

        //StringBuilder dry = new StringBuilder();
        applyConfig(worker, NedCmd.ABORT_CLI, data, null);
        worker.abortResponse();
    }


    // mangle output, we're invoked during prepare phase
    // of NCS
    @Override
    public void
    revert(NedWorker worker, String data) throws Exception {

        if (trace)
            session.setTracer(worker);

        applyConfig(worker, NedCmd.REVERT_CLI, data, null);
        worker.revertResponse();
    }


    @Override
    public void
    commit(NedWorker worker, int timeout) throws Exception  {
        worker.commitResponse();
    }


    @Override
    public void
    persist(NedWorker worker) throws Exception {
        if (trace)
            session.setTracer(worker);

        if (doAdminSaveInPersist) {
            doAdminSave();
        }
        worker.persistResponse();
    }


    @Override
    public void
    close(NedWorker worker) throws NedException, IOException {
        try {
            ResourceManager.unregisterResources(this);
        } catch (Exception ignore) {
        }
        super.close(worker);
    }


    @Override
    public void
    close() {
        try {
            ResourceManager.unregisterResources(this);
        } catch (Exception ignore) {
        }
        super.close();
    }


    @Override
    public void
    getTransId(NedWorker worker)
        throws Exception {

        byte[] bytes;
        String md5String = "";

        if (trace)
            session.setTracer(worker);

        if (this.useRollbackForTransId == true) {
            /*
             * CHECK-SYNC using rollback
             * The lines below fetches the time stamp of the
             * latest saved rollback checkpoint.
             * Does require that the rollback feature is configured
             * properly on the device.
             */
            session.println("show system rollback | match latest");
            session.expect("show system rollback");
            String res = session.expect(prompt_pattern);
            String lines[] = res.split("\n");
            String lastCommit = res;
            for (String line : lines) {
                if ( line.startsWith("latest") ) {
                    lastCommit = line;
                    break;
                }
            }

            LOGGER.info("Got last transation <" + lastCommit + ">");
            bytes = lastCommit.getBytes("UTF-8");
        }
        else {
            /*
             * CHECK-SYNC using config dump
             * This is the default method. Always works, but is slower
             */
            String res = get_config(worker);
            res += get_debug(worker);
            bytes = res.getBytes("UTF-8");
        }

        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] thedigest = md.digest(bytes);
        BigInteger md5Number = new BigInteger(1, thedigest);
        md5String = md5Number.toString(16);


        LOGGER.info("Last Transaction ID is " + md5String);
        worker.getTransIdResponse(md5String);

        return;
    }



    @Override
    public void
    show(NedWorker worker, String toptag) throws Exception {
        String res = "";
        if (trace)
            session.setTracer(worker);

        if (toptag.equals("card")) {
            res = get_config(worker);
        } else if (toptag.equals("debug")) {
            res = get_debug(worker);
        }


        worker.showCliResponse(res);
    }


    @Override
    public void
    showStats(NedWorker worker, int th, ConfPath path)
        throws Exception {
        worker.error(NedCmd.SHOW_STATS, "not implemented");
    }


    @Override
    public void
    showStatsList(NedWorker worker, int th, ConfPath path)
        throws Exception {
        worker.error(NedCmd.SHOW_STATS_LIST, "not implemented");
    }


    @Override
    public boolean isConnection(String device_id,
                                InetAddress ip,
                                int port,
                                String proto,  // ssh or telnet
                                String ruser,
                                String pass,
                                String secpass,
                                String keydir,
                                boolean trace,
                                int connectTimeout, // milliSecs
                                int readTimeout,
                                int writeTimeout) {
        return ((this.device_id.equals(device_id)) &&
            (this.ip.equals(ip)) &&
            (this.port == port) &&
            (this.proto.equals(proto)) &&
            (this.ruser.equals(ruser)) &&
            (this.pass.equals(pass)) &&
            (this.secpass.equals(secpass)) &&
            (this.trace == trace) &&
            (this.connectTimeout == connectTimeout) &&
            (this.readTimeout == readTimeout) &&
            (this.writeTimeout == writeTimeout));
    }


    @Override
    public void
    command(NedWorker worker, String cmdName, ConfXMLParam[] p)
        throws Exception {

        String cmd;
        String prefix;

        if (cmdName.equals("native-cmd")) {
            /*
             * Legacy support for the native-cmd model.
             * Shall always contain exactly one argument
             */
            prefix = "alu";
            if (p.length != 1) {
                worker.error(NedCmd.CMD, "wrong argument");
            }

            cmd = p[0].getValue().toString();

            if (cmd.startsWith("configure"))
                worker.error(NedCmd.CMD, "configure not allowed");

            System.err.println("cmd: " + cmd);

        }
        else {
            /*
             * Execute commands modeled under live-status/exec
             */
            prefix = "alu-stats";
            if (p.length < 1) {
                worker.error(NedCmd.CMD,
                             "missing argument(s) for subcmd="+cmdName);
            }

            /* Command name is first part */
            cmd = cmdName;

            /* Add arguments */
            for (int i = 0; i < p.length; ++i) {
                ConfObject val = p[i].getValue();
                if (val != null)
                    cmd += " " + val.toString();
            }
        }

        LOGGER.debug("Executing command: " +  cmd);

        session.println(cmd);
        session.expect(cmd, worker);

        String res = session.expect(prompt_pattern, worker);

        worker.commandResponse(new ConfXMLParam[]
            {
             new ConfXMLParamValue(prefix, "result", new ConfBuf(res))
            }
            );
    }


    @Override
    public NedCliBase newConnection(String device_id,
                                    InetAddress ip,
                                    int port,
                                    String proto,  // ssh or telnet
                                    String ruser,
                                    String pass,
                                    String secpass,
                                    String publicKeyDir,
                                    boolean trace,
                                    int connectTimeout, // msec
                                    int readTimeout,    // msec
                                    int writeTimeout,   // msecs
                                    NedMux mux,
                                    NedWorker worker) {
        return new AluSrNedCli(device_id, ip, port, proto, ruser, pass,
                               secpass, trace,
                               connectTimeout, readTimeout, writeTimeout,
                               mux, worker);
    }

    @Override
    public String
    toString() {
        return device_id+"-"+ip+":"+ Integer.toString(port)+"-"+proto;
    }
}
