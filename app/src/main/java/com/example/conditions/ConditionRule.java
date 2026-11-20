package com.example.conditions;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

final class ConditionRule {
    enum State { TRUE, FALSE, UNKNOWN }
    interface Reading { State evaluate(JSONObject piece); }
    static State evaluate(JSONObject rule, Reading reading) {
        try { return new Parser(rule, reading, 0).parse(); }
        catch (Exception error) { return State.UNKNOWN; }
    }
    static void validate(JSONObject rule) { new Parser(rule, p -> State.UNKNOWN, 0).parse(); }
    static JSONObject read(String json) {
        try { return new JSONObject(json); } catch (Exception error) { return new JSONObject(); }
    }
    static Set<String> used(JSONObject rule) {
        Set<String> names = new HashSet<>();
        for (String token : rule.optString("expression").toUpperCase(java.util.Locale.ROOT).split("[^A-Z0-9_]+")) {
            if (!token.isEmpty() && !token.equals("AND") && !token.equals("OR") && !token.equals("NOT")) names.add(token);
        }
        return names;
    }
    static void collect(JSONObject rule, String type, JSONArray result) {
        JSONObject pieces = rule.optJSONObject("pieces");
        if (pieces == null) return;
        for (String name : used(rule)) {
            JSONObject piece = pieces.optJSONObject(name);
            if (piece == null) continue;
            if (piece.has("expression")) collect(piece, type, result);
            else if (type.equals(piece.optString("type"))) result.put(piece);
        }
    }
    private static final class Parser {
        final String text;
        final JSONObject pieces;
        final Reading reading;
        final int depth;
        int position, nesting;
        String token;
        Parser(JSONObject rule, Reading reading, int depth) {
            if (depth > 20) throw new IllegalArgumentException("Too many nested blocks.");
            this.depth = depth; this.reading = reading;
            text = rule.optString("expression").toUpperCase(java.util.Locale.ROOT);
            pieces = rule.optJSONObject("pieces");
            if (text.length() > 2000 || pieces == null) throw new IllegalArgumentException("Add condition pieces and an expression.");
            next();
        }
        void next() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) position++;
            int start = position;
            if (position < text.length()) {
                char c = text.charAt(position++);
                if (Character.isLetter(c)) {
                    while (position < text.length() && (Character.isLetterOrDigit(text.charAt(position)) || text.charAt(position) == '_')) position++;
                } else if (c != '(' && c != ')') throw new IllegalArgumentException("Use piece names, AND, OR, NOT and parentheses.");
            }
            token = text.substring(start, position);
        }
        State parse() {
            State value = or();
            if (!token.isEmpty()) throw new IllegalArgumentException("Unexpected " + token + ". Add an operator between pieces.");
            return value;
        }
        State or() {
            State value = and();
            while (token.equals("OR")) {
                next(); State right = and();
                value = value == State.TRUE || right == State.TRUE ? State.TRUE
                        : value == State.UNKNOWN || right == State.UNKNOWN ? State.UNKNOWN : State.FALSE;
            }
            return value;
        }
        State and() {
            State value = atom();
            while (token.equals("AND")) {
                next(); State right = atom();
                value = value == State.FALSE || right == State.FALSE ? State.FALSE
                        : value == State.UNKNOWN || right == State.UNKNOWN ? State.UNKNOWN : State.TRUE;
            }
            return value;
        }
        State atom() {
            if (++nesting > 40) throw new IllegalArgumentException("Too many nested operators.");
            State value;
            if (token.equals("NOT")) {
                next(); value = atom();
                value = value == State.UNKNOWN ? value : value == State.TRUE ? State.FALSE : State.TRUE;
            } else if (token.equals("(")) {
                next(); value = or();
                if (!token.equals(")")) throw new IllegalArgumentException("Missing closing parenthesis.");
                next();
            } else {
                JSONObject piece = pieces.optJSONObject(token);
                if (piece == null || token.isEmpty()) throw new IllegalArgumentException("Choose a condition piece" + (token.isEmpty() ? "." : ": " + token));
                next(); value = piece.has("expression") ? new Parser(piece, reading, depth + 1).parse() : reading.evaluate(piece);
            }
            nesting--; return value;
        }
    }
}
