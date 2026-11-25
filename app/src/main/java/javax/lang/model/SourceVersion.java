package javax.lang.model;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Minimal stand-in: this class does not exist on Android but Rhino may reference it. */
public final class SourceVersion {
    private SourceVersion() {}

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
        "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
        "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
        "volatile", "while", "true", "false", "null", "_"));

    public static boolean isKeyword(CharSequence s) {
        return KEYWORDS.contains(s.toString());
    }

    public static boolean isIdentifier(CharSequence s) {
        String str = s.toString();
        if (str.isEmpty() || !Character.isJavaIdentifierStart(str.charAt(0))) return false;
        for (int i = 1; i < str.length(); i++) {
            if (!Character.isJavaIdentifierPart(str.charAt(i))) return false;
        }
        return true;
    }

    public static boolean isName(CharSequence s) {
        String str = s.toString();
        for (String part : str.split("\\.", -1)) {
            if (!isIdentifier(part) || isKeyword(part)) return false;
        }
        return true;
    }
}
