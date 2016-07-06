package com.qoomon.banking.swift.field.notation;

import java.text.ParseException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.*;

/**
 * <pre>
 * a = alphabetic, A through Z, upper case only
 * n = numeric digits, 0 through 9 only
 * c = alpha-numeric capital letters and numeric digits only ( a & n above )
 * d = decimals, including decimal comma ',' preceding the fractional part. The fractional part may be missing, but the decimal comman must always be present
 * e = space
 * s = sign ( + or _ )
 * h = hex ( 0 to 9, A to F)
 * x = SWIFT X character set : SPACE, A to Z, a to z, 0 to 9, and  + - / ? . : , ( ) '                   and CrLF
 * y = SWIFT Y character set : SPACE, A to Z, a to z, 0 to 9, and  + - / ? . : , ( ) ' = ! " % & * < > ;
 * z = SWIFT Z character set : SPACE, A to Z, a to z, 0 to 9, and  + - / ? . : , ( ) ' = ! " % & * < > ; and CrLf
 * A = alphabetic, A through Z, upper and lower case
 * B = alphanumeric upper case or lower case, and numeric digits
 *
 * length specification:
 * nn = maximum length ( minimum is 1 )
 * nn-nn = minimum and maximum length
 * nn! = fixed length
 * nn*nn = maximum number of lines time maximum line length
 *
 * separators
 * LSep = left separator ("/", "//", "BR" for CrLf, "ISIN ", etc.), field starts with the character specified
 * RSep = right separator ("/", "//", "BR" for CrLf, "ISIN ", etc.), field ends with the character specified
 *
 * examples,
 * 6!n = 6 numeric, fixed length
 * 6n = numeric up to 6 characters
 * 1!e = one blank space
 * 6*50x = up to 6 lines of up to 50 characters
 * </pre>
 */
public class SwiftFieldNotation {

    private static final String SEPARATOR_SET = "(?:/|//|BR|ISIN)";

    private static final Map<String, String> CHARSET_REGEX_MAP = new HashMap<String, String>() {{
        put("a", "[A-Z]");
        put("n", "[0-9]");
        put("c", "[0-9A-Z]");
        put("d", "[0-9,]");
        put("e", " ");
        put("s", "[+_]");
        put("h", "[0-9A-F]");
        put("x", "[ 0-9A-Za-z+-/?.:,()'\\n]");
        put("y", "[ 0-9A-Za-z+-/?.:,()'=!\"%&*<>;]");
        put("z", "[ 0-9A-Za-z+-/?.:,()'=!\"%&*<>;\\n]");
        put("A", "[A-Za-z]");
        put("B", "[0-9A-Za-z]");
    }};

    private final String notation;
    private final List<SubField> swiftSubFields;


    public SwiftFieldNotation(String notation) {

        this.swiftSubFields = parseSwiftNotation(notation);

        this.notation = notation;
    }

    public List<String> parse(String fieldText) throws ParseException {

        int parseIndex = 0;

        List<String> result = new LinkedList<>();

        for (SubField swiftSubField : swiftSubFields) {

            String charSet = swiftSubField.getCharSet();
            String charSetRegex = CHARSET_REGEX_MAP.get(charSet);
            if (charSetRegex == null) {
                throw new ParseException("Parse error: " + " Unknown charset " + swiftSubField.getCharSet() + "." + " near index " + parseIndex + " '" + fieldText.substring(parseIndex, Math.min(parseIndex + 8, fieldText.length())), parseIndex);
            }

            String subFieldRegex = "";
            if (swiftSubField.getLengthSign().isEmpty()) {
                subFieldRegex += charSetRegex + "{1," + swiftSubField.getLength0() + "}";
            } else if (swiftSubField.getLengthSign().equals("!")) {
                subFieldRegex += charSetRegex + "{" + swiftSubField.getLength0() + "}";
            } else if (swiftSubField.getLengthSign().equals("-")) {
                subFieldRegex += charSetRegex + "{" + swiftSubField.getLength0() + "," + swiftSubField.getLength1() + "}";
            } else if (swiftSubField.getLengthSign().equals("*")) {
                subFieldRegex += charSetRegex + "{1," + swiftSubField.getLength1() + "}";
                for (int i = 1; i < swiftSubField.getLength0(); i++) {
                    subFieldRegex += "(?:" + "\n" + charSetRegex + "{1," + swiftSubField.getLength1() + "}" + ")?";
                }
            } else {
                throw new ParseException("Unexpected length sign '" + swiftSubField.getLengthSign() + "'", parseIndex);
            }

            if (!swiftSubField.getPrefix().isEmpty()) {
                subFieldRegex = quote(swiftSubField.getPrefix()) + subFieldRegex;
            }

            if (swiftSubField.isOptional()) {
                subFieldRegex = "(?:" + subFieldRegex + ")?";
            }

            //name group for selection
            String subFieldGroupName = "subfield";
            subFieldRegex = groupRegex(subFieldGroupName, subFieldRegex);

            Matcher subFieldMatcher = Pattern.compile(subFieldRegex + ".*", DOTALL)
                    .matcher(fieldText)
                    .region(Math.min(parseIndex, fieldText.length() ), fieldText.length());
            if (!subFieldMatcher.matches()) {
                throw new ParseException(swiftSubField + " did not found matching characters."
                        + " near index " + parseIndex + " '" + fieldText.substring(parseIndex, Math.min(parseIndex + 8, fieldText.length())) + "'", parseIndex);
            }
            parseIndex = subFieldMatcher.end(subFieldGroupName);

            // special handling for d charset due to only on comma constraint
            if (charSet.equals("d")) {
                Matcher dCharsetMatcher = Pattern.compile("[^,]+,[^,]*").matcher(fieldText);
                if (!dCharsetMatcher.matches()) {
                    throw new ParseException(swiftSubField + " did not found matching characters."
                            + " near index " + parseIndex + " '" + fieldText.substring(parseIndex, Math.min(parseIndex + 8, fieldText.length())) + "'", parseIndex);
                }
            }
            String fieldValue = subFieldMatcher.group(subFieldGroupName);

            //remove prefix
            if (!swiftSubField.getPrefix().isEmpty()) {
                fieldValue = fieldValue.replaceFirst(quote(swiftSubField.prefix), "");
            }

            result.add(fieldValue.isEmpty() ? null : fieldValue);
        }

        if (parseIndex != fieldText.length()) {
            throw new RuntimeException("Unparsed characters remain."
                    + " near index " + parseIndex + " '" + fieldText.substring(parseIndex, Math.min(parseIndex + 8, fieldText.length())) + "'");
        }

        return result;
    }

    public String groupRegex(String groupName, String regex) {
        return "(?<" + groupName + ">" + regex + ")";
    }

    public String groupRegex(String groupName, Pattern pattern) {
        return groupRegex(groupName, pattern.toString());
    }

    public List<SubField> parseSwiftNotation(String swiftNotation) {
        List<SubField> result = new LinkedList<>();

        String optionalFieldGroupName = "optional";
        String mandatoryFieldGroupName = "mandatory";

        Pattern fieldValueNotationPattern = Pattern.compile("(" + SEPARATOR_SET + ")?([0-9]{1,2})(!|(?:[-*][0-9]{1,2}))?([acdehnsxyzAB])");

        Pattern fieldNotationPattern = Pattern.compile(quote("[") + groupRegex(optionalFieldGroupName, fieldValueNotationPattern) + quote("]") + "|" + groupRegex(mandatoryFieldGroupName, fieldValueNotationPattern));
        Matcher fieldNotationMatcher = fieldNotationPattern.matcher(swiftNotation);

        int parseIndex = 0;
        while (fieldNotationMatcher.find(parseIndex)) {
            if (fieldNotationMatcher.start() != parseIndex) {
                throw new RuntimeException("Parse error: near index " + parseIndex + " '" + swiftNotation.substring(parseIndex, Math.min(parseIndex + 8, swiftNotation.length()))
                        + " unexpected sign(s) '" + swiftNotation.substring(parseIndex, fieldNotationMatcher.start()) + "'");
            }
            parseIndex = fieldNotationMatcher.end();

            SubField swiftSubField = new SubField();

            String field = fieldNotationMatcher.group(mandatoryFieldGroupName);
            if (field == null) {
                field = fieldNotationMatcher.group(optionalFieldGroupName);
                swiftSubField.optional = true;
            }

            Matcher fieldPropertiesMatcher = fieldValueNotationPattern.matcher(field);
            if (!fieldPropertiesMatcher.matches()) {
                throw new RuntimeException("Parse error: near index " + parseIndex + " '" + swiftNotation.substring(parseIndex, Math.min(parseIndex + 8, swiftNotation.length()))
                        + " unexpected sign(s) '" + swiftNotation.substring(parseIndex, fieldNotationMatcher.start()) + "'");
            }

            String prefix = fieldPropertiesMatcher.group(1);
            if (prefix != null) {
                swiftSubField.prefix = prefix;
            }

            int length0 = Integer.parseInt(fieldPropertiesMatcher.group(2));
            String lengthExtra = fieldPropertiesMatcher.group(3);

            swiftSubField.length0 = length0;
            if (lengthExtra != null) {
                String lengthSign = lengthExtra.substring(0, 1);
                swiftSubField.lengthSign = lengthSign;
                switch (lengthSign) {
                    case "!":
                        break;
                    case "-":
                    case "*": {
                        swiftSubField.length1 = Integer.parseInt(lengthExtra.substring(1));
                        break;
                    }
                }
            }

            swiftSubField.charSet = fieldPropertiesMatcher.group(4);

            // add field
            result.add(swiftSubField);
        }
        if(parseIndex != swiftNotation.length()){
            throw new RuntimeException("Parse error: near index " + parseIndex + " '" + swiftNotation.substring(parseIndex, Math.min(parseIndex + 8, swiftNotation.length())));
        }
        return result;
    }

    public String getNotation() {
        return notation;
    }

    public class SubField {
        private boolean optional = false;
        private String prefix = "";
        private Integer length0 = null;
        private Integer length1 = null;
        private String charSet = null;
        public String lengthSign = "";

        public boolean isOptional() {
            return optional;
        }

        public int getLength0() {
            return length0;
        }

        public int getLength1() {
            return length1;
        }

        public String getLengthSign() {
            return lengthSign;
        }

        public String getCharSet() {
            return charSet;
        }

        public String getPrefix() {
            return prefix;
        }

        @Override
        public String toString() {
            String fieldNotation = "";

            fieldNotation += length0;
            if (!lengthSign.isEmpty()) {
                fieldNotation += lengthSign;
                if (lengthSign.equals("-") || lengthSign.equals("*")) {
                    fieldNotation += length1;
                }
            }
            fieldNotation += charSet;
            if (optional) {
                fieldNotation = "[" + prefix + fieldNotation + "]";
            }
            return fieldNotation;
        }
    }


}
