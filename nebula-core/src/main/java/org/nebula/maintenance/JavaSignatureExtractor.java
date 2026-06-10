package org.nebula.maintenance;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts {@link MethodSignature}s from decompiled Java source
 * (NEBULA-PATCH-2026-001 §变更一, §14.3.5 组件 A).
 *
 * <p>A lightweight source-level parser (not a full Java grammar): it derives the
 * package + top-level class name from the source, then scans for method
 * declarations. Decompiled Mojmaps output writes multi-line signatures, so the
 * source is first flattened (newlines → spaces) before matching, and method
 * bodies are skipped by matching on the declaration head up to the opening
 * brace or semicolon.
 *
 * <p>This is deliberately conservative: it aims to recognise the well-formed
 * method declarations that decompilers emit, not arbitrary hand-written Java.
 * Constructors, fields, and lambdas are excluded.
 */
public final class JavaSignatureExtractor {

    private static final Pattern PACKAGE = Pattern.compile("package\\s+([\\w.]+)\\s*;");
    private static final Pattern TOP_CLASS =
        Pattern.compile("(?:public\\s+|final\\s+|abstract\\s+)*(?:class|interface|enum|record)\\s+(\\w+)");

    // Modifier? returnType name(params) up to { or ;  — applied to flattened source.
    private static final Pattern METHOD = Pattern.compile(
        "(?<mods>(?:public|protected|private|static|final|abstract|synchronized|native|default)\\s+)+"
            + "(?<ret>[\\w.$<>\\[\\],\\s?]+?)\\s+"
            + "(?<name>\\w+)\\s*"
            + "\\((?<params>[^)]*)\\)\\s*"
            + "(?:throws\\s+[\\w.,\\s]+?)?\\s*[{;]");

    private JavaSignatureExtractor() {}

    /** Extracts all recognisable method signatures from one decompiled source file. */
    public static List<MethodSignature> extract(String source) {
        String ownerClass = ownerClassOf(source);
        String flat = flatten(source);

        List<MethodSignature> result = new ArrayList<>();
        Matcher m = METHOD.matcher(flat);
        while (m.find()) {
            String name = m.group("name");
            String ret = m.group("ret").trim();
            // Skip control-flow keywords that can look like a return type
            // (e.g. "if (...)") and constructors (name == class).
            if (isKeyword(ret) || isKeyword(name) || name.equals(ownerClass)) {
                continue;
            }
            List<String> mods = splitMods(m.group("mods"));
            List<String> params = parseParamTypes(m.group("params"));
            result.add(new MethodSignature(ownerClass, name, ret, params, mods));
        }
        return result;
    }

    static String ownerClassOf(String source) {
        String pkg = "";
        Matcher pm = PACKAGE.matcher(source);
        if (pm.find()) pkg = pm.group(1);
        Matcher cm = TOP_CLASS.matcher(source);
        String cls = cm.find() ? cm.group(1) : "Unknown";
        return pkg.isEmpty() ? cls : pkg + "." + cls;
    }

    /** Collapse newlines/tabs/multiple spaces so multi-line signatures match. */
    private static String flatten(String source) {
        return source.replaceAll("\\s+", " ");
    }

    private static List<String> splitMods(String mods) {
        List<String> out = new ArrayList<>();
        for (String t : mods.trim().split("\\s+")) {
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /**
     * Parses parameter types from a flattened parameter list, dropping names and
     * {@code final} qualifiers. Generics are kept (commas inside {@code <>} must
     * not split parameters).
     */
    static List<String> parseParamTypes(String params) {
        List<String> types = new ArrayList<>();
        String trimmed = params.trim();
        if (trimmed.isEmpty()) return types;

        int depth = 0;
        int start = 0;
        List<String> rawParams = new ArrayList<>();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '<' || c == '[') depth++;
            else if (c == '>' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                rawParams.add(trimmed.substring(start, i));
                start = i + 1;
            }
        }
        rawParams.add(trimmed.substring(start));

        for (String raw : rawParams) {
            String p = raw.trim();
            // Drop leading parameter annotations (e.g. "@Nullable", "@Block.UpdateFlags")
            // — possibly several — then a leading "final".
            p = p.replaceAll("^(?:@[\\w.]+\\s+)+", "");
            p = p.replaceFirst("^final\\s+", "");
            // Drop the parameter name: the type is everything up to the last token.
            int lastSpace = lastTopLevelSpace(p);
            String type = lastSpace < 0 ? p : p.substring(0, lastSpace).trim();
            // Varargs normalise to array.
            type = type.replace("...", "[]");
            if (!type.isEmpty()) types.add(type);
        }
        return types;
    }

    /** Index of the space separating type from name, ignoring spaces inside generics. */
    private static int lastTopLevelSpace(String param) {
        int depth = 0;
        int idx = -1;
        for (int i = 0; i < param.length(); i++) {
            char c = param.charAt(i);
            if (c == '<' || c == '[') depth++;
            else if (c == '>' || c == ']') depth--;
            else if (c == ' ' && depth == 0) idx = i;
        }
        return idx;
    }

    private static boolean isKeyword(String s) {
        switch (s) {
            case "if": case "for": case "while": case "switch": case "return":
            case "else": case "catch": case "synchronized": case "new":
                return true;
            default:
                return false;
        }
    }
}
