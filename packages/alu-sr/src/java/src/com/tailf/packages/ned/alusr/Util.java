package com.tailf.packages.ned.alusr;

import static java.util.Arrays.asList;

import java.util.ArrayList;
import java.util.List;

public class Util {

    /**
     * First non-null parameter.
     */
    public static <T> T first(T a, T b) {
        return a != null ? a : b;
    }

    /**
     * Convenience method to create ArrayList.
     */
    public static <T> List<T> newList() {
        return new ArrayList<T>();
    }

    @SuppressWarnings("unchecked")
    public static <T> List<T> newList(T element) {
        return new ArrayList<T>(asList(element));
    }

    @SuppressWarnings("unchecked")
    public static <T> List<T> newList(T... elements) {
        return new ArrayList<T>(asList(elements));
    }

    /**
     * Same as {@code join("", strs)}.
     */
    public static String join(List<String> strs) {
        return join("", strs);
    }

    /**
     * Join strings with separator, e.g.
     * {@code join(" ", newList("a", "b", "c")) -> "a b c"}.
     */
    public static String join(String sep, List<String> strs) {
        if (strs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Util.head(strs));
        for (String s: Util.rest(strs)) {
            sb.append(sep);
            sb.append(s);
        }
        return sb.toString();
    }

    /**
     * Append suffix to each string and return in new list.
     */
    public static List<String> append(String suffix, List<String> strs) {
        List<String> list = newList();
        for (String str: strs) {
            list.add(str + suffix);
        }
        return list;
    }

    public static <T> T head(List<T> list) {
        return list.get(0);
    }

    public static <T> List<T> rest(List<T> list) {
        return list.subList(1, list.size());
    }

    public static <T> List<T> take(int n, List<T> list) {
        int size = list.size();
        if (n > size) {
            n = size;
        }
        return list.subList(0, n);
    }

    public static <T> List<T> drop(int n, List<T> list) {
        int size = list.size();
        if (n > size) {
            n = size;
        }
        return list.subList(n, size);
    }

    public static List<String> lines(String str) {
        return asList(str.split("\r\n|\n"));
    }
}
