package com.tailf.packages.ned.ios;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.Random;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import org.apache.log4j.Logger;

import com.tailf.conf.ConfBuf;
import com.tailf.conf.ConfKey;
import com.tailf.conf.ConfObject;
import com.tailf.conf.ConfPath;
import com.tailf.conf.ConfValue;
import com.tailf.conf.ConfXMLParam;
import com.tailf.conf.ConfXMLParamValue;
import com.tailf.maapi.Maapi;
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
import com.tailf.ned.NedTTL;
import com.tailf.ned.NedTracer;
import com.tailf.ned.NedWorker;
import com.tailf.ned.NedWorker.TransactionIdMode;
import com.tailf.ned.SSHSessionException;

/**
 * This class implements NED interface for cisco ios devices
 *
 */

public class IOSNedCli extends NedCliBaseTemplate {
    public static Logger LOGGER  = Logger.getLogger(IOSNedCli.class);

    @Resource(type=ResourceType.MAAPI, scope=Scope.INSTANCE)
    public  Maapi           mm;

    private String date_string = "2015-03-16";
    private String version_string = "3.8.0";
    private final static String privexec_prompt, prompt;
    private final static Pattern[] plw, ec, ec2, config_prompt;
    private boolean waitForEcho = true;
    private boolean inConfig = false;
    private String iosdevice = "classic";
    private String iospolice = "cirmode";
    private String trimMode = "trim";  // explicit, report-all
    static {
        // start of input, > 0 non-# and ' ', one #, >= 0 ' ', eol
        privexec_prompt = "\\A[^\\# ]+#[ ]?$";

        prompt = "\\A\\S*#";

        // print_line_wait() pattern
        plw = new Pattern[] {
            //FIXME: Make sure prompt patterns begin with newline!?
            Pattern.compile("\\A.*\\(cfg\\)#"),
            Pattern.compile("\\A.*\\(config\\)#"),
            Pattern.compile("\\A.*\\(.*\\)#"),
            Pattern.compile("\\A\\S.*#"),
            // 'c'ontinue patterns:
            Pattern.compile("Continue\\?\\[confirm\\]"),
            // 'yes' patterns:
            Pattern.compile("\\? \\[yes/no\\]"),
            Pattern.compile("Do you want to destory .*\\?\\[confirm\\]"), //typo
            Pattern.compile("Continue\\? \\[yes\\]"),
        };

        config_prompt = new Pattern[] {
            Pattern.compile("\\A\\S*\\(config\\)#"),
            Pattern.compile("\\A.*\\(.*\\)#")
        };

        // config t
        ec = new Pattern[] {
            Pattern.compile("Do you want to kill that session and continue"),
            Pattern.compile("\\A\\S*\\(config\\)#"),
            Pattern.compile("\\A.*\\(.*\\)#"),
            Pattern.compile("Aborted.*\n"),
            Pattern.compile("Error.*\n"),
            Pattern.compile("syntax error.*\n"),
            Pattern.compile("error:.*\n")
        };

        ec2 = new Pattern[] {
            Pattern.compile("\\A.*\\(cfg\\)#"),
            Pattern.compile("\\A.*\\(config\\)#"),
            Pattern.compile("\\A.*\\(.*\\)#"),
            Pattern.compile("Aborted.*\n"),
            Pattern.compile("Error.*\n"),
            Pattern.compile("syntax error.*\n"),
            Pattern.compile("error:.*\n")
        };

    }

    public IOSNedCli() {
        super();
        try {
            ResourceManager.registerResources(this);
        } catch (Exception e) {
            LOGGER.error("Error injecting Resources", e);
        }
    }

    public IOSNedCli(String device_id,
               InetAddress ip,
               int port,
               String proto,  // ssh or telnet
               String ruser,
               String pass,
               String secpass,
               boolean trace,
               int connectTimeout, // msec
               int readTimeout,    // msec
               int writeTimeout,   // msec
               NedMux mux,
               NedWorker worker) {

        super(device_id, ip, port, proto, ruser, pass, secpass,
              trace, connectTimeout, readTimeout, writeTimeout, mux,
              worker);

        NedTracer tracer;

        if (trace)
            tracer = worker;
        else
            tracer = null;

        try {
            ResourceManager.registerResources(this);
        } catch (Exception e) {
            LOGGER.error("Error injecting Resources", e);
        }

        try {
            try {
                if (proto.equals("ssh")) {
                    setupSSH(worker);
                }
                else {
                    setupTelnet(worker);
                }
            }
            catch (Exception e) {
                LOGGER.error("connect failed ",  e);
                worker.connectError(NedErrorCode.CONNECT_CONNECTION_REFUSED,
                                    e.getMessage());
                return;
            }
        }
        catch (NedException e) {
            LOGGER.error("connect response failed ",  e);
            return;
        }

        try {
            NedExpectResult res;

            res = session.expect(new String[] {
                    "\\A[Ll]ogin:",
                    "\\A[Uu]sername:",
                    "\\A[Pp]assword:",
                    "\\A\\S.*>",
                    privexec_prompt},
                worker);
            if (res.getHit() < 3)
                throw new NedException(NedErrorCode.CONNECT_BADAUTH,
                                       "Authentication failed");
            if (res.getHit() == 3) {
                session.print("enable\n");
                res = session.expect(new String[] {"[Pp]assword:", prompt},
                                     worker);
                if (res.getHit() == 0) {
                    if (secpass == null || secpass.isEmpty())
                        throw new NedException(NedErrorCode.CONNECT_BADAUTH,
                                               "Secondary password "
                                               +"not set");
                    session.print(secpass+"\n"); // enter password here
                    try {
                        res = session.expect(new String[] {"\\A\\S*>", prompt},
                                             worker);
                        if (res.getHit() == 0)
                            throw new NedException(NedErrorCode.CONNECT_BADAUTH,
                                                   "Secondary password "
                                                   +"authentication failed");
                    } catch (Exception e) {
                        throw new NedException(NedErrorCode.CONNECT_BADAUTH,
                                               "Secondary password "
                                               +"authentication failed");
                    }
                }
            }

            trace(worker, "NED VERSION: cisco-ios "+version_string+" "+
                  date_string, "out");

            // Set terminal settings
            session.print("terminal length 0\n");
            session.expect("terminal length 0", worker);
            session.expect(privexec_prompt, worker);

            session.print("terminal width 0\n");
            session.expect("terminal width 0", worker);
            session.expect(privexec_prompt, worker);

            // Issue show version to check device/os type
            trace(worker, "Requesting version string", "out");
            session.print("show version\n");
            session.expect("show version");
            String version = session.expect(privexec_prompt, worker);

            /* Scan version string */
            try {
                trace(worker, "Inspecting version string", "out");

                if (version.indexOf("Cisco IOS Software") >= 0
                    // 3550 reports "show version" like this:
                    || version.indexOf("Cisco Internetwork Operating") >= 0) {

                    // Found IOS classic device
                    NedCapability capas[] = new NedCapability[2];
                    NedCapability statscapas[] = new NedCapability[1];

                    if (version.indexOf("ME340x Software") >= 0) {
                        trace(worker, "Found c3550 device", "out");
                        iosdevice = "me340x";

                    } else if (version.indexOf("C3550") >= 0) {
                        trace(worker, "Found c3550 device", "out");
                        iosdevice = "c3550"; // catalyst
                        iospolice = "numflat";

                    } else if (version.indexOf("C3750") >= 0) {
                        trace(worker, "Found c3750 device", "out");
                        iosdevice = "c3750"; // catalyst
                        iospolice = "cirflat";

                    } else if (version.indexOf("Catalyst 4500 L3") >= 0) {
                        trace(worker, "Found Catalyst 4500 L3 device", "out");
                        iosdevice = "cat4500";
                        iospolice = "cirmode-bpsflat";

                    } else if (version.indexOf("cat4500e") >= 0) {
                        trace(worker, "Found cat4500e device", "out");
                        iosdevice = "cat4500e";

                    } else if (version.indexOf("c7600") >= 0) {
                        trace(worker, "Found c7600 device", "out");
                        iosdevice ="c7600";

                    } else if (version.indexOf("Catalyst") >= 0) {
                        trace(worker, "Found catalyst device", "out");
                        iosdevice = "catalyst";
                        iospolice = "bpsflat";

                    } else if (version.indexOf("NETSIM") >= 0) {
                        trace(worker, "Found netsim device", "out");
                        iosdevice = "netsim";

                    } else if (version.indexOf("Cisco IOS XE Software") >= 0
                               || version.indexOf("IOS-XE Software") >= 0) {
                        trace(worker, "Found XE device", "out");
                        iosdevice = "XE";

                    } else if (version.indexOf("vios-") >= 0) {
                        trace(worker, "Found Vios device", "out");
                        iosdevice = "XE";

                    } else if (version.indexOf("vios_l2") >= 0) {
                        trace(worker, "Found ViOS l2 device", "out");
                        iosdevice = "c3750"; // catalyst
                        iospolice = "cirflat";

                    } else if (version.indexOf("10000 Software") >= 0) {
                        trace(worker, "Found 10000 device", "out");
                        iospolice = "numflat";

                    } else {
                        trace(worker, "Found classic device", "out");
                    }

                    capas[0] = new NedCapability(
                            "",
                            "urn:ios",
                            "tailf-ned-cisco-ios",
                            "",
                            date_string,
                            "");

                    capas[1] = new NedCapability(
                            "urn:ietf:params:netconf:capability:" +
                            "with-defaults:1.0?basic-mode=" + trimMode,
                            "urn:ietf:params:netconf:capability:" +
                            "with-defaults:1.0",
                            "",
                            "",
                            "",
                            "");

                    statscapas[0] = new NedCapability(
                            "",
                            "urn:ios-stats",
                            "tailf-ned-cisco-ios-stats",
                            "",
                            date_string,
                            "");

                    setConnectionData(capas,
                                      statscapas,
                                      true,
                                      TransactionIdMode.UNIQUE_STRING);
                } else {
                    worker.error(NedCmd.CONNECT_CLI, "unknown device");
                }
            } catch (Exception e) {
                new NedException(NedErrorCode.NED_EXTERNAL_ERROR,
                                 "Failed to read device version string");
            }
        }
        catch (SSHSessionException e) {
            worker.error(NedCmd.CONNECT_CLI, e.getMessage());
        }
        catch (IOException e) {
            worker.error(NedCmd.CONNECT_CLI, e.getMessage());
        }
        catch (Exception e) {
            worker.error(NedCmd.CONNECT_CLI, e.getMessage());
        }
    }

    @Override
    public void trace(NedWorker worker, String msg, String direction) {
        if (trace) {
            worker.trace("-- "+msg+" --\n", direction, device_id);
        }
    }

    @Override
    public void reconnect(NedWorker worker) {
        // all capas and transmode already set in constructor
        // nothing needs to be done
    }

    // Which Yang modules are covered by the class
    @Override
    public String [] modules() {
        return new String[] { "tailf-ned-cisco-ios" };
    }

    // Which identity is implemented by the class
    @Override
    public String identity() {
        return "ios-id:cisco-ios";
    }

    private void moveToTopConfig() throws IOException, SSHSessionException {
        NedExpectResult res;

        while (true) {
            session.print("exit\n");
            res = session.expect(config_prompt);
            if (res.getHit() == 0)
                return;
        }
    }

    private boolean isDevice(String device) {
        return iosdevice.equals(device);
    }

    private boolean hasPolice(String police) {
        if (iospolice.indexOf(police) >= 0)
            return true;
        else
            return false;
    }

    private boolean isCliRetry(NedWorker worker, String reply, String line) {
        String[] errretry = {
            "is in use",
            "wait for it to complete",
            "is currently being deconfigured",
            "already exists"
        };

        // Retry on these patterns:
        for (int n = 0; n < errretry.length; n++) {
            if (reply.toLowerCase().matches(".*"+errretry[n]+".*"))
                return true;
        }

        // Do not retry
        return false;
    }

    private boolean isCliError(NedWorker worker, String reply, String line) {
        int n;

        // The following strings treated as warnings -> ignore
        String[] errignore = {
            "Warning: \\S+.*",
            "AAA: Warning",
            "hqm_tablemap_inform: CLASS_REMOVE error",
            "name length exceeded the recommended length of .* characters",
            "A profile is deemed incomplete until it has .* statements"
        };

        // The following strings is an error -> abort transaction
        // NOTE: Alphabetically sorted.
        String[] errfail = {
            "aborted",
            "a .* already exists for network",
            "bad mask",
            "being used",
            "cannot apply",
            "cannot be deleted",
            "cannot configure",
            "cannot negate",
            "cannot redistribute",
            "command is depreceated",
            "command rejected",
            "configuration not accepted",
            "configure .* first",
            "create .* first",
            "disable .* first",
            "does not exist.",
            "does not support .* configurations",
            "duplicate name",
            "enable .* globally before configuring",
            "error",
            "exceeded",
            "failed",
            "first configure the",
            "has already been assigned to",
            "hash values can not exceed 255",
            "illegal hostname",
            ".* is being un/configured in sub-mode, cannot remove it",
            "in use, cannot",
            "incomplete",
            "inconsistent address.*mask",
            "interface .* already configured as default ",
            "interface.* combination tied to .* already",
            "interface .* is not associated with vrf",
            "invalid",
            "is configured as .* already",
            "is linked to a vrf. enable .* on that vrf first",
            "is not logically valid",
            "is not permitted",
            "is not running",
            "is not supported",
            "is used by",
            "may not be configured",
            "must be configured first",
            "must be disabled first",
            "must be greater than",
            "must be removed first",
            "must configure ip address for",
            "must enable .* routing first",
            "must specify a .* port as the next hop interface",
            "no existing configuration binding the default",
            "no such",
            "not allowed",
            "not a valid ",
            "not added",
            "not configured",
            "not enough memory",
            "not defined",
            "not supported in",
            "overlaps with",
            "peer* combination tied to .* already",
            "please configure .* before configuring",
            "please remove the service-policy on the zone-pair",
            "please 'shutdown' this interface before trying to delete it",
            "previously established ldp sessions may not have",
            "protocol not in this image",
            "routing not enabled",
            "setting rekey authentication rejected",
            "should be in range",
            "specify .* commands first",
            "sum total of .* exceeds 100 percent",
            "table is full",
            "unable to add",
            "unable to set_.* for ",
            "unable to populate",
            "unknown vrf specified",
            "use 'ip vrf forwarding' command for vrf",
            "use 'vrf forwarding' command for vrf",
            "vpn routing instance .* does not exist",
            "vrf specified does not match .* router"
        };

        // Special cases ugly patches
        if (line.indexOf("no ip address ") >= 0
            && reply.indexOf("Invalid address") >= 0) {
            // Happens when IP addresses already deleted on interface
            return false;
        }
        if (line.equals("no duplex")
            && reply.indexOf("Invalid input detected at") >= 0) {
            // Happens when 'no media-type' deletess duplex config on device
            return false;
        }

        // Ignore warnings
        for (n = 0; n < errignore.length; n++) {
            if (reply.matches(".*"+errignore[n]+".*")) {
                trace(worker, "ignoring warning: "+reply, "out");
                return false;
            }
        }

        // Fail on errors
        for (n = 0; n < errfail.length; n++) {
            if (reply.toLowerCase().matches(".*"+errfail[n]+".*"))
                return true;
        }

        // Success
        return false;
    }

    private void print_line_wait_oper(NedWorker worker, int cmd,
                                      String line)
        throws NedException, IOException, SSHSessionException, ApplyException {
        NedExpectResult res;

        session.print(line+"\n");
        session.expect(new String[] { Pattern.quote(line) }, worker);
        res = session.expect(new String[] {
                "Overwrite the previous NVRAM configuration\\?\\[confirm\\]",
                privexec_prompt},
            worker);
        if (res.getHit() == 0) {
            // Confirm question with "y" and wait for prompt again
            session.print("y");
            res = session.expect(new String[] {".*#"}, worker);
        }
        String lines[] = res.getText().split("\n|\r");
        for(int i = 0 ; i < lines.length ; i++) {
            if (lines[i].toLowerCase().indexOf("error") >= 0 ||
                lines[i].toLowerCase().indexOf("failed") >= 0) {
                throw new ExtendedApplyException(line, lines[i], true, false);
            }
        }
    }

    private boolean print_line_wait(NedWorker worker, int cmd, String line,
                                    int retrying)
        throws NedException, IOException, SSHSessionException, ApplyException {
        NedExpectResult res;
        boolean isAtTop;
        String lines[];

        // dirty patch to fix error that happens in timeout
        if (line.equals("config t")) {
            return true;
        }

        // tailfned
        if (line.indexOf("tailfned ") >= 0) {
            if (line.indexOf("tailfned device ") == 0) {
                iosdevice = line.substring(16);
                trace(worker, "SET tailfned device to: "+iosdevice, "out");
            }
            else if (line.indexOf("tailfned police ") == 0) {
                iospolice = line.substring(16);
                trace(worker, "SET tailfned police to: "+iospolice, "out");
            }
            if (!isDevice("netsim"))
                return true;
        }

        // Send command line + newline and wait for prompt
        session.print(line+"\n");
        if (waitForEcho)
            session.expect(new String[] { Pattern.quote(line) }, worker);
        res = session.expect(plw, worker);

        if (res.getHit() == 4) {
            // Received: Continue?[confirm]
            lines = res.getText().split("\n|\r");
            for(int i = 0 ; i < lines.length ; i++) {
                if (isCliError(worker, lines[i], line)) {
                    throw new ExtendedApplyException(line, lines[i],
                                                     false, inConfig);
                }
            }
            // Send: "c" and wait for prompt
            session.print("c");
            res = session.expect(plw);
        } else if (res.getHit() >= 5) {
            // Received:
            // ? [yes/no]
            // Do you want to destory .*?[confirm]
            // Continue? [yes]
            lines = res.getText().split("\n|\r");
            for(int i = 0 ; i < lines.length ; i++) {
                if (isCliError(worker, lines[i], line)) {
                    throw new ExtendedApplyException(line, lines[i],
                                                     false, inConfig);
                }
            }
            // Send: "yes\n" and wait for prompt
            session.print("yes\n");
            res = session.expect(plw);
        }


        // Check prompt
        if (res.getHit() == 0 || res.getHit() == 1) {
            // Top container - (cfg) || (config)
            isAtTop = true;
        } else if (res.getHit() == 2) {
            // Config mode
            isAtTop = false;
        } else if (res.getHit() == 3) {
            // non config mode - #
            inConfig = false;
            throw new ExtendedApplyException(line, "exited from config mode",
                                             false, false);
        } else {
            throw new ExtendedApplyException(line,
                                             "print_line_wait internal error",
                                             false, inConfig);
        }

        // ip sla * dirty patch
        if (res.getText().indexOf(
              "Entry already running and cannot be modified") >= 0
            && line.indexOf("ip sla ") >= 0) {
            print_line_wait(worker, cmd, "no "+line, retrying);
            return print_line_wait(worker, cmd, line, retrying);
        }

        // Look for errors
        lines = res.getText().split("\n|\r");
        for (int i = 0 ; i < lines.length ; i++) {
            if (isCliError(worker, lines[i], line)) {
                throw new ExtendedApplyException(line, lines[i], isAtTop, true);
            }
            if (isCliRetry(worker, lines[i], line)) {
                // wait a while and retry
                if (retrying > 60) {
                    // already tried enough, give up
                    throw new ExtendedApplyException(line, lines[i], isAtTop,
                                                     true);
                }
                else {
                    if (retrying == 0)
                        // #11926: changed to milliseconds
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

        return isAtTop;
    }

    private boolean enterConfig(NedWorker worker, int cmd)
        throws NedException, IOException, SSHSessionException {
        NedExpectResult res = null;

        session.print("config t\n");
        res = session.expect(ec, worker);

        if (res.getHit() > 2) {
            // Aborted | Error | syntax error | error
            worker.error(cmd, res.getText());
            return false;
        }

        else if (res.getHit() == 0) {
            // Do you want to kill that session and continue
            session.print("yes\n");
            res = session.expect(ec2, worker);
            if (res.getHit() > 2) {
                // Aborted | Error | syntax error | error
                worker.error(cmd, res.getText());
                return false;
            }
        }

        inConfig = true;
        return true;
    }

    private void exitConfig() throws IOException, SSHSessionException {
        NedExpectResult res;

        while (true) {
            session.print("exit\n");
            res = session.expect(new String[]
                {"\\A\\S*\\(config\\)#",
                 "\\A\\S*\\(cfg\\)#",
                 "\\A.*\\(.*\\)#",
                 "\\A\\S*\\(cfg.*\\)#",
                 prompt});
            if (res.getHit() == 4) {
                inConfig = false;
                return;
            }
        }
    }

    private String[] modifyData(NedWorker worker, String data)
        throws NedException {
        String lines[];
        String line, nextline;
        int i;

        // Make sure first line also starts with \n or checks will fail
        data = "\n" + data;
        // Make sure got one extra empty line or nextline may be oob
        data = data + "\n";

        //System.err.println("COMMIT_BEFORE("+device_id+")=\n"+data);
        //for (i = 0; i < data.length(); i++)
        //System.err.println("C0-"+i+"= "+ data.charAt(i));

        // policy-map - bandwidth&priority percent subtractions first
        for (i = data.indexOf("\npolicy-map "); i >= 0;
             i = data.indexOf("\npolicy-map ", i+12)) {
            int n;
            if ((n = data.indexOf("\n!", i+1)) < 0)
                continue;
            // Copy the entire policy-map into polmap
            String polmap = data.substring(i,n+2);
            if (polmap.indexOf("no bandwidth percent ") < 0
                && polmap.indexOf("no priority percent ") < 0)
                continue;
            // Strip all lines except the 'no bandwidth/priority percent'
            lines = polmap.split("\n");
            StringBuilder newlines = new StringBuilder();
            for (n = 0; n < lines.length; n++) {
                if (lines[n].indexOf("!") >= 0
                    || lines[n].indexOf("policy-map ") >= 0
                    || lines[n].indexOf("class ") >= 0
                    || lines[n].indexOf("no bandwidth percent ") >= 0
                    || lines[n].indexOf("no priority percent ") >= 0) {
                    newlines.append(lines[n]+"\n");
                }
            }
            // Add the new stripped duplicate policy-map before the original
            polmap = newlines.toString();
            System.err.println("FOUND polmap with percent = "+polmap);
            data = data.substring(0,i) + polmap + data.substring(i);
            // Skip this policy-map when looking for the next
            i = i + polmap.length();
        }

        // NON NETSIM
        if (isDevice("netsim") == false) {

            // certificate
            // FIXME: wait for echo not working
            i = data.indexOf("\n certificate ");
            while (i >= 0) {
                waitForEcho = false;
                int start = data.indexOf("\"", i+1);
                if (start > 0) {
                    int end = data.indexOf("\"", start+1);
                    if (end > 0) {
                        String cert = data.substring(start, end+1);
                        cert = stringDequote(cert);
                        data = data.substring(0,start-1) + cert + "\n"
                            + data.substring(end+1);
                    } else {
                        end = data.indexOf("\n", start+1);
                        System.err.println("MISSING end-quote for " +
                                           data.substring(i+1,end));
                    }
                }
                i = data.indexOf("\n certificate ", i + 12);
            }

        } // non-netsim ^


        // Split into lines;
        lines = data.split("\n");

        // put "no switchport" last
        for (i = 0; i < lines.length; i++) {
            line = lines[i];
            if (line.matches("^\\s*no switchport\\s*$")) {
                // found 'no switchport' only
                for (; i < lines.length; i++) {
                    nextline = lines[i+1];
                    if (!nextline.matches("^\\s*no switchport (\\S+).*$"))
                        break;
                    lines[i]   = nextline;
                    lines[i+1] = line;
                }
            }
        }

       return lines;
    }

    private String modifyLine(NedWorker worker, String line)
        throws NedException {
        int i;

        if (isDevice("netsim"))
            return line;

        // banner motd|exec|login|prompt-timeout|etc.
        if (line.matches("^\\s*banner .*$")) {
            i = line.indexOf("banner ");
            i = line.indexOf(" ",i+7);
            String banner = stringDequote(line.substring(i+1));
            banner = banner.replaceAll("\\r", "");  // device adds \r itself
            line = line.substring(0,i+1) + "^\n" + banner + "^";
            waitForEcho = false;
        }

        // ip address (without arguments)
        else if (line.matches("^\\s*ip address\\s*$")) {
            line = "!" + line;
        }

        // no disable passive-interface
        else if (line.indexOf("no disable passive-interface ") >= 0) {
            line = line.replaceAll("no disable passive-interface ",
                                   "passive-interface ");
        }

        // disable passive-interface
        else if (line.indexOf("disable passive-interface ") >= 0) {
            line = line.replaceAll("disable passive-interface ",
                                   "no passive-interface ");
        }

        // network-clock-participate
        else if (line.indexOf(
                 "network-clock-participate wic-disabled ") >= 0) {
            line = line.replaceAll(
                                   "network-clock-participate wic-disabled ",
                                   "no network-clock-participate wic ");
        }

        // no mls qos srr-queue
        else if (line.indexOf("no mls qos srr-queue ") >= 0) {
            line = line.replaceAll("no mls qos srr-queue (\\S+) (\\S+)-map .*",
                                   "no mls qos srr-queue $1 $2-map");
        }

        // police
        else if (hasPolice("bpsflat")
                 && line.indexOf("police ") >= 0) {
            // Catalyst device style: policy-map / class / police
            line = line.replaceAll("police (\\d+) bps (\\d+) byte",
                                   "police $1 $2");
        }

        return line;
    }


    // applyConfig() - apply one line at a time
    @Override
    public void applyConfig(NedWorker worker, int cmd, String data)
        throws NedException, IOException, SSHSessionException, ApplyException {
        String lines[];
        String line;
        int i;
        boolean isAtTop=true;
        long time;
        long lastTime = System.currentTimeMillis();

        if (!enterConfig(worker, cmd))
            return;

        lines = modifyData(worker, data);

        try {
            for (i = 0 ; i < lines.length ; i++) {
                time = System.currentTimeMillis();
                if ((time - lastTime) > (0.8 * writeTimeout)) {
                    lastTime = time;
                    worker.setTimeout(writeTimeout);
                }
                waitForEcho = true;
                lines[i] = lines[i].trim();
                line = modifyLine(worker, lines[i]);
                if (line == null)
                    continue;

                // Send line to device
                isAtTop = print_line_wait(worker, cmd, line, 0);
            }
        }
        catch (ApplyException e) {
            if (!e.isAtTop)
                moveToTopConfig();
            if (e.inConfigMode)
                exitConfig();
            throw e;
        }

        // make sure we have exited from all submodes
        if (!isAtTop)
            moveToTopConfig();

        exitConfig();
    }

    private class ExtendedApplyException extends ApplyException {
        public ExtendedApplyException(String line, String msg,
                                      boolean isAtTop,
                                      boolean inConfigMode) {
            super("command: "+line+": "+msg, isAtTop, inConfigMode);
         }
    }

    @Override
    public void commit(NedWorker worker, int timeout)
        throws Exception {
        if (trace)
            session.setTracer(worker);

        print_line_wait_oper(worker, NedCmd.COMMIT, "write memory");
        worker.commitResponse();
    }

    @Override
    public void prepareDry(NedWorker worker, String data)
        throws Exception {
        String lines[];
        String line;
        StringBuilder newdata = new StringBuilder();
        int i;

        lines = modifyData(worker, data);

        for (i = 0; i < lines.length; i++) {
            line = modifyLine(worker, lines[i]);
            if (line == null)
                continue;
            newdata.append(line+"\n");
        }

        worker.prepareDryResponse(newdata.toString());
    }

    @Override
    public void close(NedWorker worker)
        throws NedException, IOException {
        try {
            ResourceManager.unregisterResources(this);
        } catch (Exception ignore) {
        }
        super.close(worker);
    }

    @Override
    public void close() {
        try {
            ResourceManager.unregisterResources(this);
        } catch (Exception ignore) {
        }
        super.close();
    }

    @Override
    public void getTransId(NedWorker worker)
        throws Exception {
        int i;

        if (trace)
            session.setTracer(worker);

        // Get configuration
        String res = getConfig(worker);
        worker.setTimeout(readTimeout);

        // Calculate checksum of running-config
        byte[] bytes = res.getBytes("UTF-8");
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] thedigest = md.digest(bytes);
        BigInteger md5Number = new BigInteger(1, thedigest);
        String md5String = md5Number.toString(16);

        System.err.println("TransactionId("+device_id+") = "+md5String);
        trace(worker, "TransactionId = " + md5String, "out");

        worker.getTransIdResponse(md5String);
    }

    private String stringQuote(String aText) {
        StringBuilder result = new StringBuilder();
        StringCharacterIterator iterator =
            new StringCharacterIterator(aText);
        char character =  iterator.current();
        result.append("\"");
        while (character != CharacterIterator.DONE ){
            if (character == '"')
                result.append("\\\"");
            else if (character == '\\')
                result.append("\\\\");
            else if (character == '\b')
                result.append("\\b");
            else if (character == '\n')
                result.append("\\n");
            else if (character == '\r')
                result.append("\\r");
            else if (character == (char) 11) // \v
                result.append("\\v");
            else if (character == '\f')
                result.append("'\f");
            else if (character == '\t')
                result.append("\\t");
            else if (character == (char) 27) // \e
                result.append("\\e");
            else
                // The char is not a special one, add it to the result as is
                result.append(character);
            character = iterator.next();
        }
        result.append("\"");
        return result.toString();
    }

    private String stringDequote(String aText) {
        if (aText.indexOf("\"") != 0)
            return aText;

        aText = aText.substring(1,aText.length()-1);

        StringBuilder result = new StringBuilder();
        StringCharacterIterator iterator =
            new StringCharacterIterator(aText);
        char c1 = iterator.current();

        while (c1 != CharacterIterator.DONE ) {
            if (c1 == '\\') {
                char c2 = iterator.next();
                if (c2 == CharacterIterator.DONE )
                    result.append(c1);
                else if (c2 == 'b')
                    result.append('\b');
                else if (c2 == 'n')
                    result.append('\n');
                else if (c2 == 'r')
                    result.append('\r');
                else if (c2 == 'v')
                    result.append((char) 11); // \v
                else if (c2 == 'f')
                    result.append('\f');
                else if (c2 == 't')
                    result.append('\t');
                else if (c2 == 'e')
                    result.append((char) 27); // \e
                else {
                    result.append(c2);
                }
            }
            else {
                // The char is not a special one, add it to the result as is
                result.append(c1);
            }
            c1 = iterator.next();
        }
        return result.toString();
    }

    private static int indexOf(Pattern pattern, String s, int start) {
        Matcher matcher = pattern.matcher(s);
        return matcher.find(start) ? matcher.start() : -1;
    }

    private static String stripLineAll(String buf, String search) {
        int i = buf.indexOf(search);
        while (i >= 0) {
            int nl = buf.indexOf("\n", i+1);
            if (nl > 0)
                buf = buf.substring(0,i) + buf.substring(nl);
            i = buf.indexOf(search);
        }
        return buf;
    }

    private String getConfig(NedWorker worker)
        throws Exception {
        int i, d;
        String tailfned, override;

        session.print("show running-config\n");
        session.expect("show running-config");
        //session.print("show fixed-config\n");
        //session.expect("show fixed-config");

        String res = session.expect(privexec_prompt, worker);
        worker.setTimeout(readTimeout);

        // res=res.replaceAll("\\r", "");
        //System.err.println("SHOW_BEFORE("+device_id+")=\n"+res);

        // Strip beginning
        i = res.indexOf("Current configuration");
        if (i >= 0) {
            int n = res.indexOf("\n", i);
            if (n > 0)
                res = res.substring(n+1);
        }
        i = res.indexOf("Last configuration change");
        if (i >= 0) {
            int n = res.indexOf("\n", i);
            if (n > 0)
                res = res.substring(n+1);
        }
        i = res.indexOf("No entries found.");
        if (i >= 0) {
            int n = res.indexOf("\n", i);
            res = res.substring(n+1);
        }

        // Strip all text after and including 'end'
        i = res.lastIndexOf("\nend");
        if (i >= 0) {
            res = res.substring(0,i);
        }

        // NETSIM fixes
        if (isDevice("netsim")) {
            //System.err.println("SHOW_NETSIM("+device_id+")=\n"+res);
            return res;
        }


        //// REAL CISCO DEVICES:

        // Add tailfned police if missing
        i = res.indexOf("tailfned police ");
        if (i < 0) {
            tailfned = "\ntailfned police "+iospolice+"\n";
            res = tailfned + res;
        }
        // Add tailfned device if missing
        i = res.indexOf("tailfned device ");
        if (i < 0) {
            tailfned = "\ntailfned device "+iosdevice+"\n";
            res = tailfned + res;
        }

        // Strip out all 'NVRAM config last updated' comments
        i = res.indexOf("! NVRAM config last updated");
        while (i >= 0) {
            int n = res.indexOf("\n", i+1);
            if (n > 0)
                res = res.substring(0,i) + res.substring(n);
            i = res.indexOf("! NVRAM config last updated");
        }

        // Strip out all 'macro name'
        i = res.indexOf("\nmacro name");
        while (i >= 0) {
            int n = res.indexOf("\n@", i);
            if (n > 0)
                res = res.substring(0, i+1)+res.substring(n+2);
            i = res.indexOf("\nmacro name");
        }

        // Fix instance # vlan
        Pattern instancePattern = Pattern.compile("instance [0-9]+ vlan");
        i = indexOf(instancePattern, res, 0);
        while (i >= 0) {
            int n = res.indexOf("\n", i+1);
            if (n > 0) {
                String vlans = res.substring(i, n);
                vlans = vlans.replaceAll(", ", ",");
                res = res.substring(0, i)+vlans+res.substring(n);
            }
            i = indexOf(instancePattern, res, i+15);
        }

        // Make a single entry without add-keyword of all entries.
        i = res.indexOf("switchport trunk allowed vlan add");
        while (i >= 0) {
            int n = res.lastIndexOf("\n", i);
            if (n > 0)
                res = res.substring(0,n)+","+res.substring(i+34);
            i = res.indexOf("switchport trunk allowed vlan add", i);
        }

        // Convert interface * / ntp broadcast
        i = res.indexOf(" ntp broadcast ");
        while (i >= 0) {
            int n = res.indexOf("\n", i+16);
            if (n > 0) {
                String line = res.substring(i+1,n);
                line = line.trim();
                if ((d = line.indexOf("destination ")) > 14) {
                    // move list key "destination" first
                    line = "ntp broadcast "+line.substring(d) +
                        line.substring(13,d);
                    res = res.substring(0,i+1) + line + res.substring(n);
                }
            }
            i = res.indexOf(" ntp broadcast ", n+1);
        }

        // Remove all between boot-start-marker and boot-end-marker
        i = res.indexOf("boot-start-marker");
        if (i >= 0) {
            int n = res.indexOf("boot-end-marker", i);
            if (n >= 0) {
                n = res.indexOf("\n", n);
                if (n > 0)
                    res = res.substring(0,i)+res.substring(n+1);
            } else {
                n = res.indexOf("\n", i);
                if (n > 0)
                    res = res.substring(0,i)+res.substring(n+1);
            }
        }

        // Look for etype and convert to compact syntax (NOTE: where?)
        i = res.indexOf("etype ");
        while (i >= 0) {
            int n = res.indexOf("\n", i);
            if (n > 0) {
                String estr = res.substring(i, n);
                estr = estr.replaceAll(" , ", ",");
                res = res.substring(0,i)+estr+res.substring(n);
            }
            i = res.indexOf("etype ", i+5);
        }

        // Insert missing 'index' in ip explicit-path address entries
        for (i = res.indexOf("\nip explicit-path ");
             i >= 0;
             i = res.indexOf("\nip explicit-path ", i+16)) {
            int start = res.indexOf("\n", i+16);
            if (start < 0)
                break;
            int end = res.indexOf("!", i);
            if (end < 0)
                break;
            String buf = res.substring(start+1,end);
            String[] lines = buf.split("\n");
            int next_index = 1;
            StringBuilder newlines = new StringBuilder();
            for (int n = 0; n < lines.length; n++) {
                if (lines[n].indexOf(" index ") == 0) {
                    //Pattern p = Pattern.compile("^ index (\\d+) .*");
                    //Matcher m = p.matcher(lines[n]);
                    next_index = Integer.parseInt(lines[n].substring(7,lines[n].indexOf(" ",7)))+1;
                } else if (lines[n].indexOf(" next-address ") == 0
                           || lines[n].indexOf(" exclude-address ") == 0) {
                    lines[n] = " index "+next_index+lines[n];
                    next_index = next_index + 1;
                }
                newlines.append(lines[n]+"\n");
            }
            res = res.substring(0,start+1) + newlines.toString()
                + "\n" + res.substring(end);
        }

        // Look for crypto certificate(s) and quote contents
        i = res.indexOf("\n certificate ");
        while (i >= 0) {
            int start = res.indexOf("\n", i+1);
            //System.err.println("FOUND CERT: "+res.substring(i,start));
            if (start > 0) {
                int end = res.indexOf("quit", start);
                if (end > 0) {
                    String cert = res.substring(start+1, end);
                    res = res.substring(0,start+1) + stringQuote(cert)
                        + "\n" + res.substring(end);
                }
            }
            i = res.indexOf("\n certificate ", i+14);
        }

        // Look for banner(s), strip delimeters and quote to single string
        for (i = res.indexOf("\nbanner ");
             i >= 0;
             i = res.indexOf("\nbanner ", i+8)) {
            // banner <type> <delim>\n<MESSAGE><delim>
            int n = res.indexOf(" ", i+8);
            if (n < 0)
                continue;
            int nl = res.indexOf("\n", n);
            if (nl < 0)
                continue;
            String delim = res.substring(n+1, n+3);
            int delim2 = res.indexOf(delim, nl+1);
            if (delim2 < 0)
                continue;
            int nl2 = res.indexOf("\n", delim2);
            if (nl2 < 0)
                continue;
            String banner = res.substring(nl+1, delim2);
            //banner = banner.replaceAll("\\r", "");
            banner = stringQuote(banner);
            res = res.substring(0,n+1) + banner + res.substring(nl2);
        }

        // Strip all lines:
        res = stripLineAll(res, "\nalias ");
        res = stripLineAll(res, "\nhw-module");
        res = stripLineAll(res, "\nntp clock-period");
        res = stripLineAll(res, "\n ! Incomplete config, ");

        // DNS fixes
        res = res.replaceAll("\nip domain-name ", "\nip domain name ");
        res = res.replaceAll("\nip domain-list ", "\nip domain list ");
        res = res.replaceFirst("ip domain-lookup", "ip domain lookup");

        // AAA fixes
        res = res.replaceAll("\naaa authorization (.*)local if-authenticated",
                             "\naaa authorization $1if-authenticated local");

        // Misc fixes
        res = res.replaceFirst("\nline con 0", "\nline console 0");
        res = res.replaceAll("channel-misconfig \\(STP\\)",
                             "channel-misconfig");
        res = res.replaceAll("no passive-interface ",
                             "disable passive-interface ");
        res = res.replaceAll("no network-clock-participate wic ",
                             "network-clock-participate wic-disabled ");
        // policy-map * / class * / random-detect drops 'precedence-based' name
        res = res.replaceAll("(\\s+) random-detect(\\s*)\n",
                             "$1 random-detect precedence-based\n");

        // Line by line 'policy-map/class/police' string replacement
        i = res.indexOf(" police ");
        while (i >= 0) {
            int n = res.indexOf("\n", i+8);
            String line = res.substring(i+1,n);
            line = line.trim();
            if (line.indexOf("police cir ") == 0 ||
                line.indexOf("police rate ") == 0 ||
                line.indexOf("police aggregate ") == 0) {
                // Ignore these police lines, no transform needed
            }
            else if (line.matches("police (\\d+) bps (\\d+) byte.*")) {
                // Ignore "bpsflat " bps&byte (Catalyst) entries
            } else if (hasPolice("cirmode") || hasPolice("cirflat")) {
                // Insert missing [cir|bc|be]
                line = line.replaceAll("police (\\d+) (\\d+) (\\d+)",
                                       "police cir $1 bc $2 be $3");
                line = line.replaceAll("police (\\d+) (\\d+)",
                                       "police cir $1 bc $2");
                line = line.replaceAll("police (\\d+)",
                                       "police cir $1");
                res = res.substring(0,i+1) + line + res.substring(n);
            }
            i = res.indexOf(" police ", n+1);
        }

        //System.err.println("SHOW_AFTER("+device_id+")=\n"+res);

        // Respond with updated show buffer
        return res;
    }

    @Override
    public void show(NedWorker worker, String toptag)
        throws Exception {
        if (trace)
            session.setTracer(worker);

        if (toptag.equals("interface")) {
            System.err.println("show("+device_id+")");
            String res = getConfig(worker);
            worker.showCliResponse(res);
        } else {
            // only respond to first toptag since the IOS
            // cannot show different parts of the config.
            worker.showCliResponse("");
        }
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
                                int connectTimeout, // msec
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

    private String ConfObjectToIfName(ConfObject kp) {
        String name = kp.toString();
        name = name.replaceAll("\\{", "");
        name = name.replaceAll("\\}", "");
        name = name.replaceAll(" ", "");
        return name;
    }

    @Override
    public void command(NedWorker worker, String cmdName, ConfXMLParam[] p)
        throws Exception {
        String cmd  = cmdName;
        String reply = "";
        Pattern[] cmdPrompt;
        NedExpectResult res;
        boolean wasInConfig = inConfig;

        if (trace)
            session.setTracer(worker);

        trace(worker, "command("+device_id+") - +" + cmd + "+", "out");

        // Check number of arguments
        if (p.length < 1) {
            if (cmd.indexOf("traceroute") == 0
                || cmd.indexOf("crypto") == 0
                || cmd.indexOf("server") == 0
                || cmd.indexOf("enroll") == 0) {
                worker.error(NedCmd.CMD, "missing argument(s) for (sub)cmd="+cmdName);
                return;
            }
        }

        // Add arguments
        for (int i = 0; i < p.length; ++i) {
            ConfObject val = p[i].getValue();
            if (val != null)
                cmd = cmd + " " + val.toString();
        }

        // default command - send in config mode
        if (cmdName.indexOf("default") == 0) {
            cmdPrompt = new Pattern[] {
                Pattern.compile("\\A.*\\(.*\\)#"),
                Pattern.compile("\\A\\S.*#"),
            };

            if (!wasInConfig)
                enterConfig(worker, NedCmd.CMD);

            if (isDevice("netsim")) {
                worker.error(NedCmd.CMD, "'"+cmd+"' not supported on NETSIM, "+
                             "use a real device");
                return;
            }

            // Send command and wait for echo
            trace(worker, "command("+device_id+") - " + cmd, "out");
            System.err.println("cmd: " + cmd);
            session.print(cmd+"\n");
            session.expect(new String[] { Pattern.quote(cmd) }, worker);

            // Wait for prompt
            res = session.expect(cmdPrompt, true, readTimeout, worker);
            reply = reply + res.getText();
        }

        // crypto pki commands - send in config mode
        else if (cmd.indexOf("server ") == 0
            || cmd.indexOf("enroll ") == 0) {
            cmdPrompt = new Pattern[] {
                Pattern.compile("\\A.*\\(.*\\)#"),
                Pattern.compile("\\A\\S.*#"),
                // Question patterns:
                Pattern.compile("\\? \\[yes/no\\]")
            };

            if (!wasInConfig)
                enterConfig(worker, NedCmd.CMD);

            cmd = "crypto pki " + cmd;
            if (isDevice("netsim")) {
                worker.error(NedCmd.CMD, "'"+cmd+"' not supported on NETSIM, "+
                             "use a real device");
                return;
            }

            // Send crypto pki command and wait for echo
            trace(worker, "command("+device_id+") - " + cmd, "out");
            System.err.println("cmd: " + cmd);
            session.print(cmd+"\n");
            session.expect(new String[] { Pattern.quote(cmd) }, worker);

            // Wait for prompt, answer yes to all questions
            while (true) {
                res = session.expect(cmdPrompt, true, readTimeout, worker);
                reply = reply + res.getText();
                if (res.getHit() <= 1) {
                    break;
                }
                if (res.getHit() == 2) {
                    session.print("yes\n");
                }
            }
        }

        // crypto key command - send in config mode
        else if (cmd.indexOf("crypto ") == 0) {
            cmdPrompt = new Pattern[] {
                Pattern.compile("\\A.*\\(.*\\)#"),
                Pattern.compile("\\A\\S.*#"),
                // Continue patterns:
                Pattern.compile("Continue\\?\\[confirm\\]"),
                Pattern.compile("\\? \\[yes/no\\]"),
                Pattern.compile("How many bits in the modulus \\[(.*)\\]")
            };

            if (!wasInConfig)
                enterConfig(worker, NedCmd.CMD);

            cmd = cmd.replace("crypto ", "crypto key ");
            if (isDevice("netsim")) {
                worker.error(NedCmd.CMD, "'"+cmd+"' not supported on NETSIM, "+
                             "use a real device");
                return;
            }

            // Send crypto key command and wait for echo
            trace(worker, "command("+device_id+") - " + cmd, "out");
            System.err.println("cmd: " + cmd);
            session.print(cmd+"\n");
            session.expect(new String[] { Pattern.quote(cmd) }, worker);

            // Wait for prompt, act on some questions
            while (true) {
                res = session.expect(cmdPrompt, true, readTimeout, worker);
                reply = reply + res.getText();
                if (res.getHit() <= 1) {
                    break;
                }
                if (res.getHit() == 2) {
                    session.print("c");
                }
                else if (res.getHit() == 3) {
                    session.print("yes\n");
                }
                else if (res.getHit() == 4) { // modulus
                    session.print("512\n");
                }
            }
        }

        // exec commands
        else {
            cmdPrompt = new Pattern[] {
                Pattern.compile(prompt),
                Pattern.compile("\\A\\% .*"),
                // Continue patterns:
                Pattern.compile("\\? \\[confirm\\]"),
                Pattern.compile("\\? \\[yes/no\\]"),
                Pattern.compile(".* filename \\[.*\\]\\?")
            };

            // Send exec command and wait for echo
            trace(worker, "command("+device_id+") - " + cmd, "out");
            System.err.println("cmd: " + cmd);
            session.print(cmd + "\n");
            session.expect(new String[] { Pattern.quote(cmd) }, worker);

            // Wait for prompt, act on some questions
            while (true) {
                res = session.expect(cmdPrompt, true, readTimeout, worker);
                reply = reply + res.getText();
                if (res.getHit() <= 1) {
                    break;
                }
                if (res.getHit() == 2) {
                    // confirm?
                    session.print("y");
                }
                else if (res.getHit() == 3) {
                    // yes/no?
                    session.print("yes\n");
                }
                else if (res.getHit() == 4) {
                    // filename?
                    session.print("\n");
                }
            }

            // Report reply
            worker.commandResponse(new ConfXMLParam[] {
                    new ConfXMLParamValue("ios-stats", "result",
                                          new ConfBuf(reply))});
            return;
        }

        // Config command - exit config and report reply
        if (!wasInConfig)
            exitConfig();
        worker.commandResponse(new ConfXMLParam[] {
                new ConfXMLParamValue("ios", "result",
                                      new ConfBuf(reply))});
    }


    @Override
    public void showStats(NedWorker worker, int th, ConfPath path)
        throws Exception {

        mm.attach(th, -1, 1);

        System.err.println("showStats() "+path);

        Maapi m = mm;

        ConfObject[] kp = path.getKP();
        ConfKey x = (ConfKey) kp[1];
        ConfObject[] kos = x.elements();

        String root =
            "/ncs:devices/device{"+device_id+"}"
            +"/live-status/ios-stats:interfaces"+x;

        // Send show single interface command to device
        session.println("show interfaces "+ConfObjectToIfName(kp[1])+
                        " | include line|address");
        String res = session.expect("\\A.*#");

        // Parse single interface
        String[] lines = res.split("\r|\n");
        for(int i=0 ; i < lines.length ; i++) {
            if (lines[i].indexOf("Invalid input detected") > 0)
                throw new NedException(NedErrorCode.NED_INTERNAL_ERROR,
                                       "showStats(): Invalid input");
            if (lines[i].indexOf("Hardware is") >= 0) {
                String[] tokens = lines[i].split(" +");
                for(int k=0 ; k < tokens.length-3 ; k++) {
                    if (tokens[k].equals("address") &&
                        tokens[k+1].equals("is")) {
                        m.setElem(th, tokens[k+2], root+"/mac-address");
                    }
                }
            }
            else if (lines[i].indexOf("Internet address is") >= 0) {
                String[] tokens = lines[i].split(" +");
                m.setElem(th, tokens[4], root+"/ip-address");
            }
        }

        worker.showStatsResponse(new NedTTL[] {
                new NedTTL(new ConfPath(root+"/ip-address"), 3),
                new NedTTL(new ConfPath(root+"/mac-address"), 3)
            });

        mm.detach(th);
    }

    @Override
    public void showStatsList(NedWorker worker, int th, ConfPath path)
        throws Exception {

        System.err.println("showStatsList() "+path);

        ArrayList<NedTTL> ttls = new ArrayList<NedTTL>();

        mm.attach(th, -1, 1);

        String root =
            "/ncs:devices/device{"+device_id+"}"
            +"/live-status/ios-stats:interfaces";

        mm.delete(th, root);

        session.println("show interfaces | include line|address");
        String res = session.expect("\\A.*#");

        String[] lines = res.split("\r|\n");
        String currentInterfaceType = null;
        String currentInterfaceName = null;
        for(int i=0 ; i < lines.length ; i++) {
            if (lines[i].indexOf("line protocol") >= 0) {
                String[] tokens = lines[i].split(" +");
                Pattern pattern = Pattern.compile("\\d");
                Matcher matcher = pattern.matcher(tokens[0]);
                if (matcher.find()) {
                    currentInterfaceType =
                        tokens[0].substring(0,matcher.start());
                    currentInterfaceName =
                        tokens[0].substring(matcher.start());
                    mm.create(th, root+"{"+currentInterfaceType+
                              " "+currentInterfaceName+"}");
                }
            }
            if (currentInterfaceType != null &&
                lines[i].indexOf("Hardware is") >= 0) {
                String[] tokens = lines[i].split(" +");
                for(int x=0 ; x < tokens.length-3 ; x++) {
                    if (tokens[x].equals("address") &&
                        tokens[x+1].equals("is")) {
                        String epath =
                            root+"{"+currentInterfaceType+
                            " "+currentInterfaceName+"}"+"/mac-address";
                        mm.setElem(th, tokens[x+2], epath);
                        ttls.add(new NedTTL(new ConfPath(epath), 3));
                    }
                }
            }
            else if (currentInterfaceType != null &&
                     lines[i].indexOf("Internet address is") >= 0) {
                String[] tokens = lines[i].split(" +");
                String epath =
                    root+"{"+currentInterfaceType+" "+
                    currentInterfaceName+"}"+"/ip-address";
                mm.setElem(th, tokens[4], epath);
                ttls.add(new NedTTL(new ConfPath(epath), 3));
            }
        }

        worker.showStatsListResponse(60,
                                     ttls.toArray(new NedTTL[ttls.size()]));

        mm.detach(th);
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
        return new IOSNedCli(device_id,
                               ip, port, proto, ruser, pass, secpass, trace,
                               connectTimeout, readTimeout, writeTimeout,
                               mux, worker);
    }
}
