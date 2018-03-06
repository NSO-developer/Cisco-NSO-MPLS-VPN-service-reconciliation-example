package com.tailf.packages.ned.iosxr;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import org.apache.log4j.Logger;

import com.tailf.conf.Conf;
import com.tailf.conf.ConfBuf;
import com.tailf.conf.ConfPath;
import com.tailf.conf.ConfValue;
import com.tailf.conf.ConfObject;
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
 * This class implements NED interface for cisco iosxr routers
 *
 */

public class IosxrNedCli extends NedCliBaseTemplate {
    private static Logger LOGGER  = Logger.getLogger(IosxrNedCli.class);

    @Resource(type=ResourceType.MAAPI, scope=Scope.INSTANCE)
    public  Maapi           mm;

    private boolean inConfig = false;
    private boolean hasConfirming = false;
    private String date_string = "2015-02-09";
    private String version_string = "3.5.0.7";
    private String iosdevice = "base";
    private boolean waitForEcho = true;
    private boolean useCommitListForTransId  = false;

    // start of input, > 0 non-# and ' ', one #, >= 0 ' ', eol
    private static String prompt = "\\A[^\\# ]+#[ ]?$";

    private final static Pattern[]
        move_to_top_pattern,
        noprint_line_wait_pattern,
        print_line_wait_pattern,
        print_line_wait_confirm_pattern,
        enter_config_pattern,
        exit_config_pattern;

    static {
        move_to_top_pattern = new Pattern[] {
            Pattern.compile(".*\\(config\\)#"),
            Pattern.compile(".*\\(config.*\\)#")
        };

        noprint_line_wait_pattern = new Pattern[] {
            Pattern.compile(".*\\(config\\)#"),
            Pattern.compile(".*\\(cfg\\)#"),
            Pattern.compile(".*\\(config.*\\)#"),
            Pattern.compile(".*\\(cfg.*\\)#"),
            Pattern.compile(prompt)
        };

        print_line_wait_pattern = new Pattern[] {
            Pattern.compile(".*\\(config\\)#"),
            Pattern.compile(".*\\(cfg\\)#"),
            Pattern.compile(".*\\(config.*\\)#"),
            Pattern.compile(".*\\(cfg.*\\)#"),
            Pattern.compile(prompt)
        };

        print_line_wait_confirm_pattern = new Pattern[] {
            Pattern.compile("Are you sure"),
            Pattern.compile("Proceed"),
            Pattern.compile(".*\\(config\\)#"),
            Pattern.compile(".*\\(cfg\\)#"),
            Pattern.compile(".*\\(config.*\\)#"),
            Pattern.compile(".*\\(cfg.*\\)#"),
            Pattern.compile(prompt)
        };

        enter_config_pattern = new Pattern[] {
            Pattern.compile("\\A\\S*\\(config.*\\)#"),
            Pattern.compile("\\A\\S*#")
        };

        exit_config_pattern = new Pattern[] {
            Pattern.compile(".*\\(config\\)#"),
            Pattern.compile(".*\\(cfg\\)#"),
            Pattern.compile(".*\\(config.*\\)#"),
            Pattern.compile(".*\\(cfg.*\\)#"),
            Pattern.compile(prompt),
            Pattern.compile("You are exiting after a 'commit confirm'")
        };
    }

    public IosxrNedCli() {
        super();
        try {
            ResourceManager.registerResources(this);
        } catch (Exception e) {
            LOGGER.error("Error injecting Resources", e);
        }
    }

    public IosxrNedCli(String device_id,
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
                if (proto.equals("ssh"))
                    setupSSH(worker);
                else
                    setupTelnet(worker);
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

            // Send newline for terminal support.
            session.print("\n");

            res = session.expect(new String[] {prompt}, worker);

            // LOG NED version & date
            trace(worker, "NED VERSION: cisco-iosxr "+version_string+" "+date_string, "out");

            // Get NED configuration
            mm.setUserSession(1);
            int tid = mm.startTrans(Conf.DB_RUNNING, Conf.MODE_READ);
            String info;
            String[] paths =
                {
                    "/ncs:devices/ncs:global-settings/ncs:ned-settings",
                    "/ncs:devices/ncs:profiles/profile{cisco-iosxr}/ncs:ned-settings",
                    "/ncs:devices/device{" + device_id + "}/ncs:ned-settings",
                };

            // Get transaction ID method
            for (String p: paths) {
                p += "/cisco-iosxr-transaction-id-method";
                if (mm.exists(tid, p)) {
                    ConfValue v = mm.getElem(tid, p);
                    this.useCommitListForTransId =
                        ConfValue.getStringByValue(p, v).
                        equals("commit-list");
                }
            }

            info = this.useCommitListForTransId ?
                "rollback-timestamp" : "commit-list";
            trace(worker, "Using " + info + " for Transaction ID", "out");

            mm.finishTrans(tid);

            // Set terminal settings
            session.print("terminal length 0\n");
            session.expect("terminal length 0", worker);
            session.expect(prompt, worker);
            session.print("terminal width 0\n");
            session.expect("terminal width 0", worker);
            session.expect(prompt, worker);

            // Issue show version to check device/os type
            session.print("show version brief\n");
            session.expect("show version brief", worker);
            String version = session.expect(prompt, worker);

            /* Scan version string */
            trace(worker, "Inspecting version string", "out");
            if (version.indexOf("Cisco IOS XR Software") >= 0) {
                // found Iosxr
                NedCapability capas[] = new NedCapability[1];
                NedCapability statscapas[] = new NedCapability[1];

                if (version.indexOf("NETSIM") >= 0) {
                    trace(worker, "Found Cisco IOS XR netsim", "out");
                    iosdevice = "netsim";
                } else {
                    trace(worker, "Found Cisco IOS XR device", "out");
                }

                capas[0] = new NedCapability(
                    "",
                    "http://tail-f.com/ned/cisco-ios-xr",
                    "cisco-ios-xr",
                    "",
                    date_string,
                    "");

                statscapas[0] = new NedCapability(
                    "",
                    "http://tail-f.com/ned/cisco-ios-xr-stats",
                    "cisco-ios-xr-stats",
                    "",
                    date_string,
                    "");

                setConnectionData(capas,
                                  statscapas,
                                  false, // want reverse-diff
                                  TransactionIdMode.UNIQUE_STRING);
            } else {
                worker.error(NedCmd.CONNECT_CLI,
                             NedCmd.cmdToString(NedCmd.CONNECT_CLI),
                             "unknown device");
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

    public void trace(NedWorker worker, String msg, String direction) {
        if (trace) {
            worker.trace("-- "+msg+" --\n", direction, device_id);
        }
    }

    public void reconnect(NedWorker worker) {
        // all capas and transmode already set in constructor
        // nothing needs to be done
    }

    // Which Yang modules are covered by the class
    public String [] modules() {
        return new String[] { "tailf-ned-cisco-ios-xr" };
    }

    // Which identity is implemented by the class
    public String identity() {
        return "cisco-ios-xr-id:cisco-ios-xr";
    }

    private void moveToTopConfig() throws IOException, SSHSessionException {
        NedExpectResult res;

        while(true) {
            session.print("exit\n");
            res = session.expect(move_to_top_pattern);
            if (res.getHit() == 0)
                return;
        }
    }

    private boolean isCliError(String reply) {
        if (reply.indexOf("hqm_tablemap_inform: CLASS_REMOVE error") >= 0)
            // 'error' when "no table-map <name>", but entry is removed
            return false;

        if (reply.toLowerCase().indexOf("error") >= 0 ||
            reply.toLowerCase().indexOf("aborted") >= 0 ||
            reply.toLowerCase().indexOf("exceeded") >= 0 ||
            reply.toLowerCase().indexOf("invalid") >= 0 ||
            reply.toLowerCase().indexOf("incomplete") >= 0 ||
            reply.toLowerCase().indexOf("duplicate name") >= 0 ||
            reply.toLowerCase().indexOf("may not be configured") >= 0 ||
            reply.toLowerCase().indexOf("should be in range") >= 0 ||
            reply.toLowerCase().indexOf("is used by") >= 0 ||
            reply.toLowerCase().indexOf("being used") >= 0 ||
            reply.toLowerCase().indexOf("cannot be deleted") >= 0 ||
            reply.toLowerCase().indexOf("bad mask") >= 0 ||
            reply.toLowerCase().indexOf("failed") >= 0) {
            return true;
        }
        return false;
    }

    private boolean noprint_line_wait(NedWorker worker, int cmd, String line,
                                    int retrying)
        throws NedException, IOException, SSHSessionException, ApplyException {
        NedExpectResult res = null;
        boolean isAtTop;

        line = line.trim();

        // first, expect the echo of the line we sent
        if (waitForEcho)
            session.expect(new String[] { Pattern.quote(line) }, worker);
        // FIXME: explain prompt matching
        res = session.expect(noprint_line_wait_pattern, worker);

        if (res.getHit() == 0 || res.getHit() == 2)
            isAtTop = true;
        else if (res.getHit() == 1 || res.getHit() == 3)
            isAtTop = false;
        else
            throw new ExtendedApplyException(line, "exited from config mode",
                                     false, false);

        String lines[] = res.getText().split("\n|\r");
        for(int i = 0 ; i < lines.length ; i++) {
            if (isCliError(lines[i])) {
                // return the line we sent, and the error line string
                throw new ExtendedApplyException(line, lines[i], isAtTop, true);
            }
        }

        return isAtTop;
    }

    private boolean print_line_wait(NedWorker worker, int cmd, String line,
                                    int retrying)
        throws NedException, IOException, SSHSessionException, ApplyException {
        NedExpectResult res = null;
        boolean isAtTop;

        // Send command line + newline and wait for prompt
        session.print(line+"\n");
        if (waitForEcho)
            session.expect(new String[] { Pattern.quote(line) }, worker);
        res = session.expect(print_line_wait_pattern, worker);

        if (res.getHit() == 0 || res.getHit() == 2)
            isAtTop = true;
        else if (res.getHit() == 1 || res.getHit() == 3)
            isAtTop = false;
        else
            throw new ExtendedApplyException(line, "exited from config mode",
                                     false, false);

        String lines[] = res.getText().split("\n|\r");
        for(int i=0 ; i < lines.length ; i++) {
            if (isCliError(lines[i])) {
                throw new ExtendedApplyException(line, lines[i], isAtTop, true);
            }
        }

        return isAtTop;
    }

    private boolean print_line_wait_confirm(NedWorker worker,
                                            int cmd, String line,
                                            int retrying)
        throws NedException, IOException, SSHSessionException, ApplyException {
        NedExpectResult res = null;
        boolean isAtTop;

        session.print(line+"\n");
        session.expect(new String[] { Pattern.quote(line) }, worker);
        res = session.expect(print_line_wait_confirm_pattern, worker);

        if (res.getHit() < 2)
            return print_line_wait(worker, cmd, "y", 0);
        else if (res.getHit() == 2 || res.getHit() == 4)
            isAtTop = true;
        else if (res.getHit() == 5)
            isAtTop = false;
        else
            throw new ExtendedApplyException(line, "exited from config mode",
                                     false, false);

        String lines[] = res.getText().split("\n|\r");
        for(int i=0 ; i < lines.length ; i++) {
            if (isCliError(lines[i])) {
                throw new ExtendedApplyException(line, lines[i], isAtTop, true);
            }
        }

        return isAtTop;
    }

    private void print_line_wait_oper(NedWorker worker, int cmd,
                                      String line)
        throws NedException, IOException, SSHSessionException, ApplyException {
        NedExpectResult res = null;
        boolean isAtTop;

        session.print(line+"\n");
        session.expect(new String[] { Pattern.quote(line) }, worker);
        res = session.expect(new String[] {prompt}, worker);

        String lines[] = res.getText().split("\n|\r");
        for(int i=0 ; i < lines.length ; i++) {
            if (lines[i].toLowerCase().indexOf("error") >= 0 ||
                lines[i].toLowerCase().indexOf("failed") >= 0) {
                throw new ExtendedApplyException(line, lines[i], true, false);
            }
        }
    }

    private boolean enterConfig(NedWorker worker, int cmd)
        throws NedException, IOException, SSHSessionException {
        NedExpectResult res = null;

        session.print("config exclusive\n");
        res = session.expect(enter_config_pattern, worker);
        if (res.getHit() > 0) {
            worker.error(cmd, NedCmd.cmdToString(cmd), res.getText());
            return false;
        }

        inConfig = true;

        return true;
    }

    private void exitConfig() throws IOException, SSHSessionException {
        NedExpectResult res;

        while(true) {
            session.print("exit\n");
            res = session.expect(exit_config_pattern);
            if (res.getHit() == 4) {
                inConfig = false;
                return;
            }
            else if (res.getHit() == 5) {
                session.print("yes\n");
                session.expect(prompt);
                inConfig = false;
                return;
            }
        }
    }

    private String[] modifyData(NedWorker worker, String data)
        throws NedException {
        int i;
        String lines[];

        if (iosdevice.equals("netsim") == true) {
            return data.split("\n");
        }

        // route-policy
        // "if (xxx) then \r\n statement(s) \r\n endif\r\n"
        // end-policy
        // -> dequote single quoted string to make multiple lines
        if ((i = data.indexOf("route-policy ")) != 0)  // may start commit!
            i = data.indexOf("\nroute-policy ");
        while (i >= 0) {
            //waitForEcho = false;
            int start = data.indexOf("\"", i+1);
            if (start > 0) {
                int end = data.indexOf("\"", start+1);
                if (end > 0) {
                    String buf = data.substring(start, end+1);
                    buf = stringDequote(buf);
                    data = data.substring(0,start-1) + buf + "\n"
                        + data.substring(end+1);
                } else {
                    end = data.indexOf("\n", start+1);
                    System.err.println("MISSING end-quote for " +
                                       data.substring(i+1,end));
                }
            }
            i = data.indexOf("\nroute-policy ", i + 12);
        }

        // Split into lines;
        lines = data.split("\n");

        // Strip 'no xxx' entries inside sets.
        //   extcommunity-set rt *
        //   rd-set *
        //   prefix-set *
        //   as-path-set *
        //   community-set *
        for (i = 0; i < lines.length; i++) {
            if (lines[i].matches("^\\s*extcommunity-set rt \\S+\\s*$")
                || lines[i].matches("^\\s*rd-set \\S+\\s*$")
                || lines[i].matches("^\\s*prefix-set \\S+\\s*$")
                || lines[i].matches("^\\s*as-path-set \\S+\\s*$")
                || lines[i].matches("^\\s*community-set \\S+\\s*$")) {
                for (i = i + 1; i < lines.length; i++) {
                    if (lines[i].matches("^\\s*end-set\\s*$"))
                        break;
                    else if (lines[i].matches("^\\s*no \\S+.*$"))
                        lines[i] = null;
                 // Add missing comma to all but the last line
                 // else if (!lines[i+1].matches("^\\s*end-set\\s*$")
                 // && lines[i].indexOf(",") < 0)
                 //    lines[i] = lines[i] + ",";
                }
            }
        }

        return lines;
    }

    public void applyConfig(NedWorker worker, int cmd, String data)
        throws NedException, IOException, SSHSessionException, ApplyException {
        // apply one line at a time
        String lines[];
        String chunk;
        int i, n;
        boolean isAtTop=true;
        long time;
        long lastTime = System.currentTimeMillis();

        waitForEcho = true;

        if (!enterConfig(worker, cmd))
            // we encountered an error
            return;

        // Modify data and split into lines
        lines = modifyData(worker, data);

        LOGGER.info("applyConfig() length: " + Integer.toString(lines.length));

        try {
            if (waitForEcho == true) {
                // Send chunk of 1000
                for (i = 0; i < lines.length; i += 1000) {
                    for (chunk = "", n = i; n < lines.length && n < (i + 1000); n++)
                        if (lines[n] != null)
                            chunk = chunk + lines[n] + "\n";
                    //LOGGER.info("sending chunk("+i+")");
                    session.print(chunk);

                    // Check result of one line at the time
                    for (n = i; n < lines.length && n < (i + 1000); n++) {
                        if (lines[n] == null)
                            continue;
                        // Set a large timeout if needed
                        time = System.currentTimeMillis();
                        if ((time - lastTime) > (0.8 * writeTimeout)) {
                            lastTime = time;
                            worker.setTimeout(writeTimeout);
                        }
                        isAtTop = noprint_line_wait(worker, cmd, lines[n], 0);
                    }
                }
            }

            else {
                // Don't want echo, send one line at a time
                for (i = 0 ; i < lines.length ; i++) {
                    if (lines[i] == null)
                        continue;
                    time = System.currentTimeMillis();
                    if ((time - lastTime) > (0.8 * writeTimeout)) {
                        lastTime = time;
                        worker.setTimeout(writeTimeout);
                    }

                    // Send line
                    isAtTop = print_line_wait(worker, cmd, lines[i], 0);
                }
            }
        }
        catch (ApplyException e) {
            if (!e.isAtTop)
                moveToTopConfig();
            throw e;
        }

        // make sure we have exited from all submodes
        if (!isAtTop)
            moveToTopConfig();

        // Temporary - this should really be done in commit, but then
        // NCS should also be able to abort after commit.
        /*
             prepare (send data to device)
                 /   \
                v     v
             abort | commit(send confirmed commit (ios would do noop))
                      /   \
                     v     v
                 revert | persist (send confirming commit)
        */
        try {
            // FIXME: if there is only one device involved, we should
            // do normal commit instead.
            time = System.currentTimeMillis();
            if ((time - lastTime) > (0.8 * readTimeout)) {
                lastTime = time;
                worker.setTimeout(readTimeout);
            }
            print_line_wait(worker, cmd, "commit confirmed", 0);
        }
        catch (ApplyException e) {
            // if confirmed commit failed, invoke this special command
            // in order to figure out what went wrong
            String line = "show configuration failed";
            session.println(line);
            session.expect(line, worker);
            String msg = session.expect(prompt, worker);
            if (msg.indexOf("No such configuration") >= 0) {
                // this means there is no last failed error saved
                throw e;
            } else {
                throw new ExtendedApplyException(line, msg, e.isAtTop, e.inConfigMode);
            }
        }
    }

    public void prepareDry(NedWorker worker, String data)
        throws Exception {
        String lines[];
        StringBuilder newdata = new StringBuilder();
        int i;

        // Modify data
        lines = modifyData(worker, data);

        // Concatenate lines into a single string
        for (i = 0; i < lines.length; i++) {
            if (lines[i] != null)
                newdata.append(lines[i]+"\n");
        }

        worker.prepareDryResponse(newdata.toString());
    }

    public void abort(NedWorker worker, String data)
        throws Exception {

        session.setTracer(worker);

        print_line_wait_oper(worker, NedCmd.ABORT_CLI, "abort");
        inConfig = false;
        worker.abortResponse();
    }

    public void revert(NedWorker worker, String data)
        throws Exception {

        session.setTracer(worker);

        print_line_wait_oper(worker, NedCmd.REVERT_CLI, "abort");
        inConfig = false;
        worker.revertResponse();
    }

    private class ExtendedApplyException extends ApplyException {
        public ExtendedApplyException(String line, String msg,
                                      boolean isAtTop,
                                      boolean inConfigMode) {
            super("command: "+line+": "+msg, isAtTop, inConfigMode);
            //xr:this.isAtTop = isAtTop;
            //xr:this.inConfigMode = inConfigMode;
            //xr:inConfig = inConfigMode;
         }
    }

    public void commit(NedWorker worker, int timeout)
        throws Exception {
        session.setTracer(worker);

        // Temporary - see applyConfig above.
        /*
        if (timeout < 0) {
            print_line_wait(worker, NedCmd.COMMIT, "commit", 0);
            hasConfirming = false;
        } else if (timeout < 30) {
            print_line_wait(worker, NedCmd.COMMIT, "commit confirmed 30",
                            0);
            hasConfirming = true;
        } else {
            print_line_wait(worker, NedCmd.COMMIT, "commit confirmed "+
                            timeout, 0);
            hasConfirming = true;
        }
        */
        worker.commitResponse();
    }

    public void persist(NedWorker worker) throws Exception {
        session.setTracer(worker);

        // Temporary - see applyConfig above.
        //        if (hasConfirming)
        if (inConfig) {
            print_line_wait(worker, NedCmd.COMMIT, "commit", 0);
            exitConfig();
        }
        worker.persistResponse();
    }

    public void close(NedWorker worker)
        throws NedException, IOException {
        try {
            ResourceManager.unregisterResources(this);
        } catch (Exception ignore) {
        }
        super.close(worker);
    }

    public void close() {
        try {
            ResourceManager.unregisterResources(this);
        } catch (Exception ignore) {
        }
        super.close();
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
                if (c2 == CharacterIterator.DONE)
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
                else
                    result.append(c2);
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

    private String getConfig(NedWorker worker)
        throws Exception {
        int i;

        if (inConfig) {
            session.print("do show running-config\n");
            session.expect("do show running-config", worker);
        }
        else {
            session.print("show running-config\n");
            session.expect("show running-config", worker);
            //session.print("show fixed-config\n");
            //session.expect("show fixed-config", worker);
        }

        String res = session.expect(prompt, worker);
        worker.setTimeout(readTimeout);

        i = res.indexOf("Building configuration...");
        if (i >= 0) {
            int n = res.indexOf("\n", i);
            if (n > 0)
                res = res.substring(n+1);
        }

        i = res.indexOf("!! Last configuration change:");
        if (i >= 0) {
            int n = res.indexOf("\n", i);
            if (n > 0)
                res = res.substring(n+1);
        }

        i = res.indexOf("No entries found.");
        if (i >= 0) {
            int n = res.indexOf("\n", i);
            if (n > 0)
                res = res.substring(n+1);
        }

        // Strip everything after 'end'
        i = res.lastIndexOf("\nend");
        if (i >= 0) {
            res = res.substring(0,i);
        }

        // Top-trick xxyyzztop must always be set to avoid sync diff
        res = "xxyyzztop 0\n" + res;

        // NETSIM, no need to modify running config more.
        if (iosdevice.equals("netsim") == true) {
            return res;
        }


        //// REAL DEVICES BELOW:

        // Cut everything between boot-start-marker and boot-end-marker
        i = res.indexOf("boot-start-marker");
        if (i >= 0) {
            int x = res.indexOf("boot-end-marker");
            if (x > i) {
                int n = res.indexOf("\n", x);
                if (n > 0)
                    res = res.substring(0,i)+res.substring(n+1);
            }
        }

        // Remove archive
        i = res.indexOf("\narchive");
        if (i >= 0) {
            int x = res.indexOf("\n!", i);
            if (x >= 0) {
                res = res.substring(0,i)+res.substring(x);
            } else {
                res = res.substring(0,i);
            }
        }

        // look for banner(s) and quote banner-text, e.g.:
        // \nbanner motd c "banner-text" c, where 'c' is a delimiting character
        i = res.indexOf ("\nbanner ");
        while (i >= 0) {
            int n = res.indexOf(" ", i+8);
            if (n > 0) {
                int start_banner = n+2;
                String delim = res.substring(n+1, n+2);   // 'c'
                if (delim.equals("^")) {
                    // ^C delimiter, i.e. 2 characters
                    delim = res.substring(n+1,n+3);
                    start_banner = n+3;
                }
                int end_i = res.indexOf(delim, start_banner);
                if (end_i > 0) {
                    String banner = stringQuote(res.substring(start_banner,
                                                              end_i));
                    res = res.substring(0,n+1)+delim+" "+banner+" "+delim
                        +res.substring(end_i+delim.length());
                }
            }
            i = res.indexOf ("\nbanner ", i + 8);
        }

        // route-policy, quote lines
        i = res.indexOf("\nroute-policy ");
        while (i >= 0) {
            int start = res.indexOf("\n", i+1);
            if (start > 0) {
                int end = res.indexOf("\nend-policy", start);
                if (end > 0) {
                    String buf = res.substring(start+1, end+1);
                    res = res.substring(0,start+1) + stringQuote(buf)
                        + "\n" + res.substring(end+1);
                }
            }
            i = res.indexOf("\nroute-policy ", i + 12);
        }

        // Mode-sensitive interface fix.
        res = res.replaceAll("\ninterface ", "\nxxyyzztop 0\ninterface ");

        // Add "" around descriptions.
        //res = res.replaceAll("^\\s+description (.*)\n", " description \\\"$1\\\"\n");

        System.err.println("SHOW_AFTER("+device_id+")=\n"+res);

        // Respond with updated show buffer
        return res;
    }

    public void getTransId(NedWorker worker)
        throws Exception {
        String res;

        if (trace)
            session.setTracer(worker);
        trace(worker, "getTransIdResponse("+device_id+") - begin", "out");

        // Use commit list ID for string data
        if (useCommitListForTransId) {
            session.print("show configuration commit list 1\n");
            session.expect("show configuration commit list 1", worker);
            res = session.expect(prompt, worker);
            int i = res.indexOf("SNo.");
            if (i >= 0)
                res = res.substring(i);
        }

        // Use running-config for string data
        else {
            res = getConfig(worker);
        }
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

    public void show(NedWorker worker, String toptag)
        throws Exception {
        session.setTracer(worker);

        if (toptag.equals("interface")) {
            System.err.println("show("+device_id+")");
            trace(worker, "show("+device_id+") - begin", "out");
            String res = getConfig(worker);
            trace(worker, "show("+device_id+") - end", "out");
            worker.showCliResponse(res);
        } else {
            // only respond to first toptag since Iosxr
            // cannot show different parts of the config.
            worker.showCliResponse("");
        }
    }

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

    public void command(NedWorker worker, String cmdName, ConfXMLParam[] p)

        throws Exception {
        String cmd  = cmdName;
        String reply = "";
        Pattern[] cmdPrompt;
        NedExpectResult res;

        if (trace)
            session.setTracer(worker);

        if (p.length < 1) {
            worker.error(NedCmd.CMD, "missing argument(s) for subcmd="+cmdName);
        }

        /* Add arguments */
        for (int i = 0; i < p.length; ++i) {
            ConfObject val = p[i].getValue();
            if (val != null)
                cmd = cmd + " " + val.toString();
        }

        // crypto key command
        if (cmd.indexOf("crypto ") == 0) {
            cmdPrompt = new Pattern[] {
                Pattern.compile("\\A.*\\(.*\\)#"),
                Pattern.compile("\\A\\S.*#"),
                // Continue patterns:
                Pattern.compile("Continue\\?\\[confirm\\]"),
                Pattern.compile("\\? \\[yes/no\\]"),
                Pattern.compile("How many bits in the modulus \\[(.*)\\]")
            };

            cmd = cmd.replace("crypto ", "crypto key ");
            if (iosdevice.equals("netsim") == true) {
                worker.error(NedCmd.CMD, "'"+cmd+"' not supported on NETSIM, "+
                             "use a real device");
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
                    session.print("1024\n");
                }
            }
        }

        // show|clear|ping|traceroute|copy|reload command - exec mode
        else {
            cmdPrompt = new Pattern[] {
                Pattern.compile(prompt),
                Pattern.compile("\\A\\% .*"),
                // Continue patterns:
                Pattern.compile("\\? \\[confirm\\]"),
                Pattern.compile("\\? \\[yes/no\\]")
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
                    session.print("c");
                }
                else if (res.getHit() == 3) {
                    session.print("yes\n");
                }
            }
        }

        // Report reply
        worker.commandResponse(new ConfXMLParam[] {
                new ConfXMLParamValue("cisco-ios-xr-stats", "result",
                                      new ConfBuf(reply))});
    }

    public void showStats(NedWorker worker, int th, ConfPath path)
        throws Exception {

        worker.showStatsResponse(new NedTTL[] {
                new NedTTL(path, 10)
            });
    }

    public void showStatsList(NedWorker worker, int th, ConfPath path)
        throws Exception {
        worker.showStatsListResponse(10, null);
    }

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
        return new IosxrNedCli(device_id,
                               ip, port, proto, ruser, pass, secpass, trace,
                               connectTimeout, readTimeout, writeTimeout,
                               mux, worker);
    }

    public String toString() {
        if (ip == null)
            return device_id+"-<ip>:"+Integer.toString(port)+"-"+proto;
        return device_id+"-"+ip.toString()+":"+
            Integer.toString(port)+"-"+proto;
    }
}

