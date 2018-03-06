package com.tailf.packages.ned.alusr;

import java.util.regex.Pattern;

/**
 * Utility for filtering / converting native config commands
 * before they are passed to NCS.
 *
 * @author jrendel
 *
 */
public class ConfigCleaner {

    private static class Replacer {
        final Pattern pattern;
        final String replacement;

        Replacer(String regex, String replacement) {
            this.pattern = Pattern.compile(regex);
            this.replacement = replacement;
        }

        String replace(CharSequence str) {
            return pattern.matcher(str).replaceAll(replacement);
        }
    }

    private static Replacer replacer(String regex, String replacement) {
        return new Replacer(regex, replacement);
    }

    private final static Replacer[] replacers =
    {
     replacer("(?m)^(#|echo).*(\r\n|\n|\r)", ""),
     //replacer("(?m)^ *((\r\n)|\n|\r)", ""),
     replacer("(?m)^ *(\r)", ""),
     // replacer("(?m)^ *no .*(\r\n|\n|\r)", ""),
     replacer("(?m)create *$", ""),

     // Default key for router is "Base" and is not shown when
     // reading config so we add it back.
     replacer("(?m)^( *)router( *)$", "$1router \"Base\"$2"),

     // Default instance id for router * / osfp is "0" and is not
     // shown when reading config so we add it back.
     replacer("(?m)^( *)ospf( *)$", "$1ospf \"0\"$2"),

     // Same as above but with router id on the same line. Need to
     // add back instance id "0".
     replacer("(?m)^( *)ospf( \\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})( *)$",
              "$1ospf \"0\"$2"),

     // Default key for router * / isis is "0" and is not
     // shown when reading config so we add it back.
     replacer("(?m)^( *)isis( *)$", "$1isis 0$2"),

     // urpf-check means urpf-check mode strict.
     // The first is shown when reading config so
     // we transform to the later.
     replacer("(?m)^( *)urpf-check( *)$", "$1urpf-check mode strict$2"),

     // When talking to netsim it is necessary to convert the
     // ! character marking end-of-mode with a proper exit.
     replacer("(?m)^( *)!$","$1exit")
    };


    /**
     * Filter the config command line
     *
     * @param txt
     *
     * @return filtered command
     */
    public String cleanAll(String txt) {
        for (Replacer r: replacers) {
            txt = r.replace(txt);
        }

        int i;

        i = txt.indexOf("configure");

        if (i >= 0)
            txt = txt.substring(i+10);

        i = txt.indexOf("exit all");

        if (i >= 0)
            txt = txt.substring(0,i-1);

        return txt;
    }

}
