package com.springtime.bigquery.service;

import com.springtime.bigquery.error.InvalidQueryException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlGuardTest {

    private final SqlGuard guard = new SqlGuard();

    @Test
    void allowsPlainSelect() {
        assertThatCode(() -> guard.assertReadOnly("SELECT 1")).doesNotThrowAnyException();
    }

    @Test
    void allowsCommonReadOnlyStatements() {
        assertThatCode(() -> guard.assertReadOnly("WITH x AS (SELECT 1) SELECT * FROM x"))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.assertReadOnly("  /* report */\n-- comment\nSELECT * FROM t"))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.assertReadOnly("EXPLAIN SELECT 1")).doesNotThrowAnyException();
        assertThatCode(() -> guard.assertReadOnly("DESCRIBE `p.d.t`")).doesNotThrowAnyException();
        assertThatCode(() -> guard.assertReadOnly("SHOW TABLES")).doesNotThrowAnyException();
    }

    @Test
    void allowsSingleTrailingSemicolon() {
        assertThatCode(() -> guard.assertReadOnly("SELECT 1;")).doesNotThrowAnyException();
        assertThatCode(() -> guard.assertReadOnly("SELECT 1 ;  \n")).doesNotThrowAnyException();
    }

    @Test
    void allowsATrailingCommentAfterTheSemicolon() {
        assertThatCode(() -> guard.assertReadOnly("SELECT 1; -- done"))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.assertReadOnly("SELECT 1; /* done */"))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.assertReadOnly("SELECT 1; -- done\n/* still done */\n"))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsSemicolonsInsideStringsAndComments() {
        assertThatCode(() -> guard.assertReadOnly("SELECT ';'")).doesNotThrowAnyException();
        assertThatCode(() -> guard.assertReadOnly("SELECT \"a;b\"")).doesNotThrowAnyException();
        assertThatCode(() -> guard.assertReadOnly("SELECT `a;b` FROM t")).doesNotThrowAnyException();
        assertThatCode(() -> guard.assertReadOnly("SELECT 1 -- ;\n")).doesNotThrowAnyException();
        assertThatCode(() -> guard.assertReadOnly("SELECT 1 # ;\n")).doesNotThrowAnyException();
        assertThatCode(() -> guard.assertReadOnly("SELECT /* ; */ 1")).doesNotThrowAnyException();
        assertThatCode(() -> guard.assertReadOnly("SELECT 'it''s; fine'"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsDmlAndDdl() {
        for (String sql : new String[]{
                "INSERT INTO t VALUES (1)",
                "UPDATE t SET x = 1",
                "DELETE FROM t",
                "CREATE TABLE t (x INT64)",
                "DROP TABLE t",
                "MERGE t USING s ON t.id = s.id WHEN MATCHED THEN DELETE",
                "GRANT `roles/bigquery.dataViewer` ON TABLE t TO 'user:x@example.com'"}) {
            assertThatThrownBy(() -> guard.assertReadOnly(sql))
                    .as(sql)
                    .isInstanceOf(InvalidQueryException.class);
        }
    }

    @Test
    void rejectsMultipleStatements() {
        assertThatThrownBy(() -> guard.assertReadOnly("SELECT 1; SELECT 2"))
                .isInstanceOf(InvalidQueryException.class)
                .hasMessageContaining("Multiple statements");
        assertThatThrownBy(() -> guard.assertReadOnly("SELECT 1 /* ; */ ; SELECT 2"))
                .isInstanceOf(InvalidQueryException.class)
                .hasMessageContaining("Multiple statements");
    }

    @Test
    void rejectsBlankSql() {
        assertThatThrownBy(() -> guard.assertReadOnly("   "))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> guard.assertReadOnly(null))
                .isInstanceOf(InvalidQueryException.class);
    }
}
