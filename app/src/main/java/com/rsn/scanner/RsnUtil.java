package com.rsn.scanner;

import java.util.Locale;

/** RSN पहचानने का नियम: सिर्फ़ "R" से शुरू होने वाला और जिसमें कम से कम एक अंक हो। */
final class RsnUtil {
    private RsnUtil() { }

    /** स्कैन किए टेक्स्ट में से पहला मान्य RSN लौटाता है, नहीं मिला तो "" । */
    static String extract(String text) {
        if (text == null) return "";
        String[] parts = text.toUpperCase(Locale.ROOT).split("[^A-Z0-9]+");
        for (String part : parts) {
            if (part.length() > 0 && part.charAt(0) == 'R' && containsDigit(part)) {
                return part;
            }
        }
        return "";
    }

    private static boolean containsDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') return true;
        }
        return false;
    }
}
