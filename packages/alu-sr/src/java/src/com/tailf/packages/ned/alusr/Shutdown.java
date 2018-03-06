package com.tailf.packages.ned.alusr;

import static java.util.Collections.unmodifiableSet;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;

import com.tailf.conf.ConfBool;
import com.tailf.conf.ConfValue;
import com.tailf.maapi.Maapi;
import com.tailf.maapi.MaapiSchemas;
import com.tailf.maapi.MaapiSchemas.CSNode;
import com.tailf.maapi.MaapiSchemas.CSSchema;
import com.tailf.ncs.ns.Ncs;

/**
 * Utility used for handling the usage of shutdown / no shutdown
 * commands used frequently on the alu-sr device.
 *
 * A dynamic config entry can typically not be removed before
 * it has been shutdown properly.
 *
 * It is typically necessary to automatically convert NCS CLI commands
 * that refer to dynamic entries uting the trick below:
 *
 * From:
 *   no <dynamic entry>
 * To:
 *   <dynamic entry> shutdown
 *   no <dynamic entry>
 *
 * @author jrendel
 *
 */
public class Shutdown {
    private static Set<String> tags;
    private static final Logger LOGGER = Logger.getLogger(Shutdown.class);
    private static String deviceId;

    /**
     * Load necessary info from MAAPI Schemas.
     * Requires that MAAPI Schemas has been loaded with
     * {@linkplain Maapi#loadSchemas()}.
     */
    public static
    void load(String id) {

        deviceId = id;

        if (tags != null) {
            return;
        }
        tags = unmodifiableSet(findShutdownTagsInSchemas());
    }


    /**
     * Get the corresponding {@code shutdown} command for a
     * {@code no} command, or {@code null} if not a {@code no}
     * command or no {@code shutdown} command should be issued.
     *
     * @param line    - command line
     * @param lines   - current command line sequence
     * @param current - current position in the command line sequence
     *
     * @return {@code shutdown} command or {@code null}
     */
    public static String
    getShutdownCmd(String line, List<String> lines, int current) {
        Pattern[] nocmd_patterns =
            new Pattern[] {
                           // Standard pattern used for lists etc
                           Pattern.compile("(.*)no (([-\\w]+)(?: .*))$"),
                           // Solves special cases
                           Pattern.compile(
                                 "(.*)no ((ldp|bgp|bgp-ad|mpls|rsvp|server))$")
        };

        if (line.contains("shutdown") || line.contains("exit"))
            return null;

        // Match line for all valid patterns
        for (Pattern p : nocmd_patterns) {
            Matcher m = p.matcher(line);

            if (!m.matches())
                continue;

            String path = getPath("", lines, current, false);
            String tag = path + m.group(3) + "/";
            String cmd = m.group(2);
            String prefix = m.group(1);

            if (!tags.contains(tag)) {
                if (LOGGER.isDebugEnabled())
                    LOGGER.debug("No match for tag " + tag);
                return null;
            }

            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Shutdown for cmd " + path + line);

            return prefix + cmd + " shutdown";
        }

        // Nothing found
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("No match for " + line);
        }

        return null;
    }


    /**
     * Get tags of nodes that contain {@code shutdown} sub-nodes.
     * @return {@code Set} of tags
     */
    public static Set<String>
    getTags() {
        return tags;
    }


    /**
     * Count leading whitespaces.
     * Used to determine mode context of the command line.
     *
     * Assuming there are no trailing spaces, so trim is used.
     *
     * @return {@number of whitespaces}
     */
    private static int
    leadingSpaces(String line)
    {
        return line.length() - line.trim().length();
    }


    /**
     * Recursively build a path for a leaf/container in a line
     * in the command line list represented by the index c.
     *
     * @param path      - the path
     * @param lines     - command sequence
     * @param c         - current index in sequence
     * @param doKeyPath - if true, build a key path. Otherwise a schema path.
     *
     * @return {@path} as a string.
     */
    private static String
    getPath(String path, List<String> lines, int c, boolean doKeyPath)
    {
        /*
         * Step backwards through the command lines, starting
         * from the line above the current (c).
         */
        int spacesAtStart = leadingSpaces(lines.get(c));

        for (int i = c - 1; i >= 0; i--) {
            /*
             * Compare leading whitespaces between the lines.
             */
            if (lines.get(i).length() > 0) {
                int spacesAtCurrent = leadingSpaces(lines.get(i));
                boolean found = false;

               if (lines.get(c).trim().equals("!")) {
                   /*
                    * Current line is an exit command. Search backwards
                    * for the command that caused the mode switch. This
                    * will be the next level in the path
                    */

                   if (spacesAtStart == spacesAtCurrent) {
                       found = true;
                   }
               }
               else if (spacesAtStart > spacesAtCurrent) {
                /*
                 * For other commands, next level in the path is
                 * found when number spaces at current < spaces at start.
                 */
                   found = true;
               }

               if (found) {
                   /*
                    * Trim next level path, strip arguments and call
                    * recursively;
                    */
                   if (doKeyPath) {

                       /*
                        * Include {keys} in path
                        */
                       String[] components = lines.get(i).trim().split(" ");
                       if (components.length > 1) {
                           path = components[0] + "{" + components[1] + "}/"
                                   + path;
                       }
                       else {
                           path = components[0] + "/" + path;
                       }
                   }
                   else {
                       /*
                        * Plain schema path
                        */
                       path = lines.get(i).trim().split(" ")[0] + "/" + path;
                   }

                   return getPath(path, lines, i, doKeyPath);
               }
            }
        }

        return path;
    }


    /**
     * Find tags of nodes that contain {@code shutdown} sub-nodes.
     *
     * @return {@code Set} of tags
     */
    private static Set<String>
    findShutdownTagsInSchemas() {
        MaapiSchemas schemas = Maapi.getSchemas();
        CSSchema schema = schemas.findCSSchema(Ncs.hash);
        CSNode root = schema.getRootNode()
            .getSibling(Ncs._devices)
            .getChild(Ncs._device)
            .getChild(Ncs._config);

        Set<String> tags = new HashSet<String>();
        lookupShutdownParentsByTag(tags, root);
        return tags;
    }

    /**
     * nodeHasShutdownLeaf
     *
     * Checks if this node has a corresponding shutdown leaf or not.
     *
     * @param line    - current command line
     * @param lines   - array with all config to apply
     * @param current - current position in config array
     *
     * @return true   - has shutdown leaf
     *         false  - has no shutdown leaf
     */
    public static boolean
    nodeHasShutdownLeaf(String line, List<String> lines, int current) {
        Pattern p = Pattern.compile("(\\S+) (\\S+).*");
        Matcher m = p.matcher(line);

        String path = getPath("", lines, current, false);
        String tag;
        if (m.matches()) {
            tag = path + m.group(1) + "/";
        }
        else {
            tag = path + line + "/";
        }

        if (!tags.contains(tag)) {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("No match for tag " + tag);
            return false;
        }

        return true;
    }

    /**
     * parentHasShutdownLeaf
     *
     * Checks if this nodes parent has a corresponding shutdown leaf or not.
     *
     * @param lines   - array with all config to apply
     * @param current - current position in config array
     *
     * @return true   - has shutdown leaf
     *         false  - has no shutdown leaf
     */
    public static boolean
    parentHasShutdownLeaf(List<String> lines, int current) {

        String tag = getPath("", lines, current, false);

        if (!tags.contains(tag)) {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("No match for parent tag " + tag);
            return false;
        }

        return true;
    }

    /**
     * Checks via Maapi the current state of the nodes shutdown leaf
     *
     * @param mm      - Maapi handle
     * @param th      - Transaction handle
     * @param line    - current command line
     * @param lines   - array containing all config to apply
     * @param current - current position in config array
     *
     * @return true  - node IS shutdown
     *         false - node IS NOT shutdown
     */
    public static boolean
    getCurrentState(Maapi mm, int th, String line,
                     List<String> lines, int current) {

        Pattern p = Pattern.compile("(\\S+) (\\S+).*");
        Matcher m = p.matcher(line);

        String path = getPath("", lines, current, true);

        if (m.matches()) {
            path = path + m.group(1) + "{" + m.group(2) + "}";
        }
        else if (!line.trim().equals("exit")) {
            path += line;
        }

        path = "/ncs:devices/ncs:device{"
            + deviceId + "}/config/" + path + "/shutdown";

        try {
            CSNode node = Maapi.getSchemas().findCSNode(Ncs.uri, path);

            if (node.isEmptyLeaf()) {
                /*
                 * shutdown leaf of type empty
                 */
                if (mm.exists(th, path)) {
                    return true;
                }
                else {
                    return false;
                }
            }
            else {
                /*
                 * shutdown leaf of type boolean
                 */
                ConfBool val;
                if (mm.exists(th, path)) {
                    val = (ConfBool) mm.getElem(th, path);
                }
                else {
                     val = (ConfBool) node.getDefval();
                }

                return val.booleanValue();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Recursively find all list entries that have a shutdown leaf
     * mapped to it.
     *
     * @param tags - list with entries using 'shutdown'
     * @param node - a leaf in the config tree.
     */
    private static void
    lookupShutdownParentsByTag(Set<String> tags, CSNode node) {
        if (node.getTag().equals("shutdown")) {
            String path = "";
            CSNode parent = node.getParentNode();

            while (parent != null && !parent.getTag().equals("config")) {
                path = parent.getTag() + "/" + path;
                parent = parent.getParentNode();
            }

            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Adding tag: " + path);

            tags.add(path);
        }
        if (node.getChildren() != null) {
            for (CSNode child: node.getChildren()) {
                lookupShutdownParentsByTag(tags, child);
            }
        }
    }
}
