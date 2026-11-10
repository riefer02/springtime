package com.springtime.bigquery.service;

import com.springtime.bigquery.error.InvalidQueryException;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Cheap first line of defence for the read-only mode. It skips leading comments, accepts a small
 * allow-list of statement keywords and rejects anything that looks like a second statement.
 *
 * <p>This is not a SQL parser. The authoritative controls are the BigQuery IAM roles granted to the
 * runtime service account (job user + data viewer) and {@code maximumBytesBilled}. Run the service
 * with a read-only identity if you expose it to untrusted callers.</p>
 */
@Component
public class SqlGuard {

    private static final Pattern READ_ONLY_STATEMENT = Pattern.compile(
            "^\\s*(?:(?:/\\*.*?\\*/|--[^\\n]*(?:\\n|$)|#[^\\n]*(?:\\n|$))\\s*)*(select|with|explain|describe|show)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public void assertReadOnly(String sql) {
        String candidate = stripTrailingSemicolons(sql);
        if (candidate.isBlank()) {
            throw new InvalidQueryException("sql must not be blank");
        }
        if (hasStatementSeparator(candidate)) {
            throw new InvalidQueryException(
                    "Multiple statements are not allowed in read-only mode; submit one statement per request");
        }
        if (!READ_ONLY_STATEMENT.matcher(candidate).find()) {
            throw new InvalidQueryException(
                    "Only SELECT, WITH, EXPLAIN, DESCRIBE and SHOW statements are allowed in read-only mode. "
                            + "Set BIGQUERY_READ_ONLY=false to allow DML/DDL.");
        }
    }

    private static String stripTrailingSemicolons(String sql) {
        String candidate = sql == null ? "" : sql;
        int end = candidate.length();
        while (end > 0) {
            char c = candidate.charAt(end - 1);
            if (Character.isWhitespace(c) || c == ';') {
                end--;
            } else {
                break;
            }
        }
        return candidate.substring(0, end);
    }

    /**
     * Finds a statement separator outside string literals and comments. This keeps legitimate
     * queries such as {@code SELECT ';'} working while still rejecting {@code SELECT 1; SELECT 2}.
     */
    private static boolean hasStatementSeparator(String sql) {
        boolean singleQuote = false;
        boolean doubleQuote = false;
        boolean backtick = false;
        boolean lineComment = false;
        boolean blockComment = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (singleQuote) {
                if (c == '\\') {
                    i++;
                } else if (c == '\'') {
                    if (next == '\'') {
                        i++;
                    } else {
                        singleQuote = false;
                    }
                }
                continue;
            }
            if (doubleQuote) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    if (next == '"') {
                        i++;
                    } else {
                        doubleQuote = false;
                    }
                }
                continue;
            }
            if (backtick) {
                if (c == '`') {
                    if (next == '`') {
                        i++;
                    } else {
                        backtick = false;
                    }
                }
                continue;
            }

            if (c == '-' && next == '-') {
                lineComment = true;
                i++;
            } else if (c == '#') {
                lineComment = true;
            } else if (c == '/' && next == '*') {
                blockComment = true;
                i++;
            } else if (c == '\'') {
                singleQuote = true;
            } else if (c == '"') {
                doubleQuote = true;
            } else if (c == '`') {
                backtick = true;
            } else if (c == ';') {
                if (!onlyCommentsAndWhitespace(sql, i + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A semicolon followed only by comments/whitespace is a single-statement terminator. */
    private static boolean onlyCommentsAndWhitespace(String sql, int start) {
        int i = start;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && next == '-') {
                i += 2;
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '#') {
                i++;
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && next == '*') {
                i += 2;
                while (i + 1 < sql.length()
                        && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                if (i + 1 < sql.length()) {
                    i += 2;
                } else {
                    i = sql.length();
                }
            } else {
                return false;
            }
        }
        return true;
    }
}
